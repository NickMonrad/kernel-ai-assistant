#include <android/log.h>
#include <jni.h>

#include "llama.h"
#include "json-schema-to-grammar.h"
#include "json.h"

#include <array>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

struct ProbeSession {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    std::mutex generation_mutex;
    std::atomic<int64_t> cancelled_request{0};
    std::atomic<bool> generating{false};

    ~ProbeSession() {
        if (context != nullptr) llama_free(context);
        if (model != nullptr) llama_model_free(model);
    }
};

std::once_flag backend_once;

void throw_java(JNIEnv * env, const char * class_name, const std::string & message) {
    jclass exception_class = env->FindClass(class_name);
    if (exception_class != nullptr) env->ThrowNew(exception_class, message.c_str());
}

std::string from_java(JNIEnv * env, jstring value, const char * label) {
    if (value == nullptr) throw std::invalid_argument(std::string(label) + " must not be null");
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) throw std::runtime_error(std::string("Unable to read ") + label);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

ProbeSession * session_from(jlong handle) {
    auto * session = reinterpret_cast<ProbeSession *>(handle);
    if (session == nullptr) throw std::invalid_argument("Invalid llama.cpp session handle");
    return session;
}

void batch_set_tokens(llama_batch_ext * batch, const llama_token * tokens, int32_t count, llama_pos position) {
    llama_batch_ext_clear(batch);
    for (int32_t index = 0; index < count; ++index) {
        const int32_t batch_index = llama_batch_ext_add_token(batch, 0, tokens[index]);
        const llama_pos token_position = position + index;
        llama_batch_ext_set_pos(batch, batch_index, &token_position);
    }
    llama_batch_ext_set_output_logits(batch, count - 1, true);
}

int32_t token_piece(const llama_vocab * vocab, llama_token token, char * buffer, int32_t capacity) {
    const int32_t bytes = llama_token_to_piece(vocab, token, buffer, capacity, 0, true);
    if (bytes < 0) throw std::runtime_error("llama.cpp token-to-piece buffer was too small");
    return bytes;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kernel_ai_debug_llamaprobe_LlamaCppSession_00024Companion_nativeOpen(
        JNIEnv * env, jobject, jstring model_path, jint context_size) {
    try {
        std::call_once(backend_once, [] { llama_backend_init(); });
        const std::string path = from_java(env, model_path, "modelPath");
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0; // CPU-only prototype; no GPU offload claim.
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(path.c_str(), model_params), &llama_model_free);
        if (model == nullptr) throw std::runtime_error("llama.cpp could not load the GGUF model");

        auto session = std::make_unique<ProbeSession>();
        session->vocab = llama_model_get_vocab(model.get());
        session->model = model.release();
        llama_context_params context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(context_size);
        context_params.n_batch = static_cast<uint32_t>(context_size);
        context_params.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
        context_params.n_threads_batch = context_params.n_threads;
        context_params.no_perf = true;
        session->context = llama_init_from_model(session->model, context_params);
        if (session->context == nullptr) throw std::runtime_error("llama.cpp could not create the CPU context");
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kernel_ai_debug_llamaprobe_LlamaCppSession_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jlong request_id, jstring prompt, jstring system_prompt,
        jstring schema_json, jint max_tokens, jboolean progress_logging) {
    try {
        ProbeSession * session = session_from(handle);
        std::lock_guard<std::mutex> guard(session->generation_mutex);
        if (max_tokens <= 0 || max_tokens > 2048) throw std::invalid_argument("maxTokens must be between 1 and 2048");
        session->generating.store(true);
        struct GeneratingReset {
            ProbeSession * value;
            ~GeneratingReset() { value->generating.store(false); }
        } generating_reset{session};

        const std::string user_prompt = from_java(env, prompt, "prompt");
        const std::string system = from_java(env, system_prompt, "systemPrompt");
        const std::string schema_text = from_java(env, schema_json, "schemaJson");
        if (user_prompt.size() > 64 * 1024 || system.size() > 16 * 1024 || schema_text.size() > 64 * 1024) {
            throw std::invalid_argument("Probe prompt or schema exceeds the bounded input size");
        }
        if (session->cancelled_request.load() == request_id) throw std::runtime_error("Generation cancelled");

        const auto request_start = std::chrono::steady_clock::now();
        const bool report_progress = progress_logging == JNI_TRUE;
        if (report_progress) {
            __android_log_print(ANDROID_LOG_INFO, "LlamaProbeNative", "event=request_start max_tokens=%d", max_tokens);
            __android_log_print(ANDROID_LOG_INFO, "LlamaProbeNative", "event=grammar_start");
        }
        const common_json schema = common_json::parse(schema_text);
        const std::string grammar = json_schema_to_grammar(schema);
        if (report_progress) {
            __android_log_print(ANDROID_LOG_INFO, "LlamaProbeNative", "event=grammar_complete");
        }
        using SamplerPtr = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
        SamplerPtr sampler(llama_sampler_chain_init(llama_sampler_chain_default_params()), &llama_sampler_free);
        if (!sampler) throw std::runtime_error("Could not create sampler chain");
        llama_sampler * grammar_sampler = llama_sampler_init_grammar(session->vocab, grammar.c_str(), "root");
        if (grammar_sampler == nullptr) throw std::invalid_argument("llama.cpp rejected the generated JSON grammar");
        llama_sampler_chain_add(sampler.get(), grammar_sampler);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());

        const std::string formatted_prompt =
            "<|im_start|>system\n" + system + "<|im_end|>\n" +
            "<|im_start|>user\n" + user_prompt + "<|im_end|>\n" +
            "<|im_start|>assistant\n";
        if (report_progress) {
            __android_log_print(ANDROID_LOG_INFO, "LlamaProbeNative", "event=tokenize_start");
        }
        const int32_t prompt_token_count = -llama_tokenize(
            session->vocab, formatted_prompt.c_str(), static_cast<int32_t>(formatted_prompt.size()), nullptr, 0, true, true);
        if (prompt_token_count <= 0 || prompt_token_count + max_tokens > static_cast<int32_t>(llama_n_ctx(session->context))) {
            throw std::invalid_argument("Prompt plus generation exceeds the configured context window");
        }
        std::vector<llama_token> prompt_tokens(static_cast<size_t>(prompt_token_count));
        if (llama_tokenize(
                session->vocab, formatted_prompt.c_str(), static_cast<int32_t>(formatted_prompt.size()),
                prompt_tokens.data(), prompt_token_count, true, true) < 0) {
            throw std::runtime_error("llama.cpp failed to tokenize the ChatML prompt");
        }
        if (report_progress) {
            __android_log_print(
                ANDROID_LOG_INFO, "LlamaProbeNative", "event=tokenize_complete prompt_tokens=%d", prompt_token_count);
        }

        using BatchPtr = std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)>;
        BatchPtr batch(llama_batch_ext_init(session->context), &llama_batch_ext_free);
        if (!batch) throw std::runtime_error("Could not allocate llama.cpp batch");

        batch_set_tokens(batch.get(), prompt_tokens.data(), prompt_token_count, 0);
        const auto prefill_start = report_progress
            ? std::chrono::steady_clock::now()
            : std::chrono::steady_clock::time_point{};
        if (report_progress) {
            __android_log_print(
                ANDROID_LOG_INFO, "LlamaProbeNative", "event=prefill_start prompt_tokens=%d", prompt_token_count);
        }
        if (llama_process(session->context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
            throw std::runtime_error("llama.cpp prompt evaluation failed");
        }
        if (report_progress) {
            const auto prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - prefill_start).count();
            __android_log_print(
                ANDROID_LOG_INFO, "LlamaProbeNative", "event=prefill_complete prompt_tokens=%d elapsed_ms=%lld",
                prompt_token_count, static_cast<long long>(prefill_ms));
        }

        std::string output;
        int32_t generated_tokens = 0;
        int64_t ttft_ms = -1;
        auto first_token_at = request_start;
        bool completed = false;
        int32_t position = prompt_token_count;
        std::array<char, 4096> token_buffer{};
        while (generated_tokens < max_tokens) {
            if (session->cancelled_request.load() == request_id) throw std::runtime_error("Generation cancelled");
            const int32_t token_index = generated_tokens + 1;
            const auto sample_start = report_progress
                ? std::chrono::steady_clock::now()
                : std::chrono::steady_clock::time_point{};
            if (report_progress) {
                __android_log_print(
                    ANDROID_LOG_INFO, "LlamaProbeNative", "event=sample_start token_index=%d", token_index);
            }
            const llama_token next_token = llama_sampler_sample(sampler.get(), session->context, -1);
            if (report_progress) {
                const auto sampled_at = std::chrono::steady_clock::now();
                const auto sample_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    sampled_at - sample_start).count();
                __android_log_print(
                    ANDROID_LOG_INFO, "LlamaProbeNative",
                    "event=sample_complete token_index=%d sample_ms=%lld",
                    token_index, static_cast<long long>(sample_ms));
            }
            if (llama_vocab_is_eog(session->vocab, next_token)) {
                completed = true;
                break;
            }
            if (report_progress) {
                __android_log_print(
                    ANDROID_LOG_INFO, "LlamaProbeNative", "event=token_piece_start token_index=%d", token_index);
            }
            const int32_t piece_bytes = token_piece(
                session->vocab, next_token, token_buffer.data(), static_cast<int32_t>(token_buffer.size()));
            output.append(token_buffer.data(), static_cast<size_t>(piece_bytes));
            if (generated_tokens == 0) {
                first_token_at = std::chrono::steady_clock::now();
                ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(first_token_at - request_start).count();
            }
            ++generated_tokens;
            if (report_progress) {
                __android_log_print(
                    ANDROID_LOG_INFO, "LlamaProbeNative", "event=token_piece_complete generated_tokens=%d",
                    generated_tokens);
                __android_log_print(
                    ANDROID_LOG_INFO, "LlamaProbeNative", "event=token_decode_start generated_tokens=%d",
                    generated_tokens);
            }
            batch_set_tokens(batch.get(), &next_token, 1, position++);
            const auto decode_start = report_progress
                ? std::chrono::steady_clock::now()
                : std::chrono::steady_clock::time_point{};
            if (llama_process(session->context, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) {
                throw std::runtime_error("llama.cpp token evaluation failed");
            }
            if (report_progress) {
                const auto decode_done = std::chrono::steady_clock::now();
                const auto decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    decode_done - decode_start).count();
                const auto request_elapsed_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    decode_done - request_start).count();
                __android_log_print(
                    ANDROID_LOG_INFO, "LlamaProbeNative",
                    "event=token_decode_complete generated_tokens=%d decode_ms=%lld request_elapsed_ms=%lld",
                    generated_tokens, static_cast<long long>(decode_ms), static_cast<long long>(request_elapsed_ms));
            }
        }

        const auto done = std::chrono::steady_clock::now();
        const int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(done - request_start).count();
        if (report_progress) {
            __android_log_print(
                ANDROID_LOG_INFO, "LlamaProbeNative",
                "event=request_complete generated_tokens=%d complete=%d total_ms=%lld",
                generated_tokens, completed ? 1 : 0, static_cast<long long>(total_ms));
        }
        const int64_t decode_ms = generated_tokens == 0
            ? 0
            : std::chrono::duration_cast<std::chrono::milliseconds>(done - first_token_at).count();
        common_json result = {
            {"text", output},
            {"complete", completed},
            {"prompt_tokens", prompt_token_count},
            {"generated_tokens", generated_tokens},
            {"ttft_ms", std::max<int64_t>(ttft_ms, 0)},
            {"decode_ms", decode_ms},
            {"total_ms", total_ms},
        };
        const std::string json_result = result.dump();
        return env->NewStringUTF(json_result.c_str());
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kernel_ai_debug_llamaprobe_LlamaCppSession_nativeCancel(
        JNIEnv * env, jobject, jlong handle, jlong request_id) {
    try {
        session_from(handle)->cancelled_request.store(request_id);
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_kernel_ai_debug_llamaprobe_LlamaCppSession_nativeIsGenerating(
        JNIEnv * env, jobject, jlong handle) {
    try {
        return session_from(handle)->generating.load() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kernel_ai_debug_llamaprobe_LlamaCppSession_nativeClose(
        JNIEnv * env, jobject, jlong handle) {
    try {
        delete session_from(handle);
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
    }
}
