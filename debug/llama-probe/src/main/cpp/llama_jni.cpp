#include <android/log.h>
#include <jni.h>

#include "llama.h"
#include "json-schema-to-grammar.h"
#include "json.h"
#include "common.h"
#include "sampling.h"
#include "speculative.h"

#include <array>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <functional>
#include <time.h>
#include <limits>

#include <vector>

namespace {

struct ProbeSession {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    const llama_vocab * vocab = nullptr;
    common_speculative_init_result_ptr speculative_init;
    common_speculative_ptr speculative;
    int32_t draft_n_max = 0;
    std::mutex generation_mutex;
    std::atomic<int64_t> active_request_id{0};
    std::atomic<int64_t> cancelled_request{0};
    std::atomic<bool> abort_logged{false};

    ~ProbeSession() {
        speculative.reset();
        speculative_init.reset();
        if (context != nullptr) llama_free(context);
        if (model != nullptr) llama_model_free(model);
    }
};

bool abort_if_cancelled(void * user_data) {
    auto * session = static_cast<ProbeSession *>(user_data);
    const int64_t active_request_id = session->active_request_id.load(std::memory_order_acquire);
    if (active_request_id == 0 ||
        session->cancelled_request.load(std::memory_order_acquire) != active_request_id) {
        return false;
    }
    if (!session->abort_logged.exchange(true, std::memory_order_relaxed)) {
        __android_log_print(ANDROID_LOG_INFO, "JandalLlamaProbe",
                            "event=prefill_abort_callback request_id=%lld",
                            static_cast<long long>(active_request_id));
    }
    return true;
}

struct ActiveRequestGuard {
    ProbeSession & session;
    ~ActiveRequestGuard() {
        session.active_request_id.store(0, std::memory_order_release);
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
std::string format_model_prompt(
        const llama_model * model, const std::string & system, const std::string & user) {
    const char * template_source = llama_model_chat_template(model, nullptr);
    if (template_source == nullptr) {
        throw std::runtime_error("The LFM2.5 GGUF does not provide a chat template");
    }
    const std::string source(template_source);
    const std::string escaped_prefix = "<|im_start|>assistant\\n";
    const std::string newline_prefix = "<|im_start|>assistant\n";
    const bool has_escaped_generation_prefix =
        source.find(escaped_prefix + "<think>") != std::string::npos;
    const bool has_newline_generation_prefix =
        source.find(newline_prefix + "<think>") != std::string::npos;
    const std::string & generation_prefix = has_escaped_generation_prefix ? escaped_prefix : newline_prefix;
    const std::string generation_suffix_marker = generation_prefix + "<think>";
    const size_t suffix_marker_position = source.find(generation_suffix_marker);
    if (source.find("{{- bos_token -}}") == std::string::npos ||
        source.find("<|im_start|>system") == std::string::npos ||
        source.find("message.role") == std::string::npos ||
        source.find("<|im_end|>") == std::string::npos ||
        suffix_marker_position == std::string::npos) {
        throw std::runtime_error(
            "The pinned llama.cpp v0.7.0 chat API does not support this LFM2.5 GGUF template");
    }

    const llama_chat_message messages[] = {
        {"system", system.c_str()},
        {"user", user.c_str()},
    };
    const int32_t required = llama_chat_apply_template(
        template_source, messages, 2, true, nullptr, 0);
    if (required <= 0 || required > 128 * 1024) {
        throw std::runtime_error("llama.cpp could not format the LFM2.5 GGUF chat template");
    }
    std::vector<char> buffer(static_cast<size_t>(required) + 1);
    const int32_t written = llama_chat_apply_template(
        template_source, messages, 2, true, buffer.data(), required + 1);
    if (written != required) {
        throw std::runtime_error("llama.cpp returned an incomplete LFM2.5 chat prompt");
    }

    std::string formatted(buffer.data(), static_cast<size_t>(written));
    const std::string expected =
        "<|im_start|>system\n" + system + "<|im_end|>\n" +
        "<|im_start|>user\n" + user + "<|im_end|>\n" +
        "<|im_start|>assistant\n";
    if (formatted != expected) {
        throw std::runtime_error(
            "The pinned llama.cpp v0.7.0 formatter cannot faithfully apply this LFM2.5 template");
    }

    // v0.7.0's C API formats the model's ChatML body but omits LFM2.5's
    // metadata-provided generation suffix. Only append it for the exact
    // supported suffix above; tokenization adds the GGUF's BOS token.
    formatted.append(
        source,
        suffix_marker_position + generation_prefix.size(),
        generation_suffix_marker.size() - generation_prefix.size());
    return formatted;
}

jbyteArray to_java_bytes(JNIEnv * env, const std::string & value) {
    if (value.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        throw std::runtime_error("Native result exceeds the Java byte-array limit");
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(value.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(
        result, 0, static_cast<jsize>(value.size()),
        reinterpret_cast<const jbyte *>(value.data()));
    return result;
}


std::int64_t process_cpu_time_ns() {
    timespec cpu_time{};
    if (::clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &cpu_time) != 0) return -1;
    return static_cast<std::int64_t>(cpu_time.tv_sec) * 1000000000LL + cpu_time.tv_nsec;
}

struct DsparkGenerationResult {
    int32_t generated_tokens = 0;
    int32_t drafted_tokens = 0;
    int32_t accepted_draft_tokens = 0;
    int64_t prefill_ms = 0;
    int64_t ttft_ms = -1;
    int64_t decode_ms = 0;
    bool complete = false;
};

DsparkGenerationResult generate_dspark(
        ProbeSession & session,
        const std::vector<llama_token> & prompt_tokens,
        common_sampler_ptr & sampler,
        int32_t max_tokens,
        std::chrono::steady_clock::time_point request_start,
        bool report_progress,
        const std::function<void(llama_token)> & emit_token) {
    if (session.speculative == nullptr || session.speculative_init == nullptr ||
        session.speculative_init->context() == nullptr || prompt_tokens.empty()) {
        throw std::logic_error("DSpark session state is incomplete");
    }

    constexpr llama_seq_id seq_id = 0;
    llama_context * const draft_context = session.speculative_init->context();
    const common_context_seq_rm_type target_rm_type = common_context_can_seq_rm(session.context);
    const common_context_seq_rm_type draft_rm_type = common_context_can_seq_rm(draft_context);
    const bool use_ckpt_tgt = target_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_FULL;
    const bool use_ckpt_dft = draft_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_FULL;
    if (target_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_NO ||
        draft_rm_type == COMMON_CONTEXT_SEQ_RM_TYPE_NO) {
        throw std::runtime_error("DSpark target or draft context does not support request-state reset");
    }

    // Every request starts a new prompt at position zero in both contexts.
    llama_memory_clear(llama_get_memory(session.context), false);
    llama_memory_clear(llama_get_memory(draft_context), false);

    llama_tokens prompt_tgt(prompt_tokens.begin(), prompt_tokens.end() - 1);
    common_batch batch_prompt(session.context);
    for (size_t i = 0; i < prompt_tokens.size() - 1; ++i) {
        batch_prompt.add(prompt_tokens[i], static_cast<llama_pos>(i), seq_id, false);
    }

    DsparkGenerationResult result;
    const auto prefill_start = std::chrono::steady_clock::now();
    if (report_progress) {
        __android_log_print(
            ANDROID_LOG_INFO, "LlamaProbeNative",
            "event=dspark_prefill_start prompt_tokens=%d", static_cast<int>(prompt_tokens.size()));
    }
    const int prefill_status = llama_process(
        session.context, LLAMA_PROCESS_TYPE_DECODE, batch_prompt.get());
    if (prefill_status != 0) {
        if (abort_if_cancelled(&session)) throw std::runtime_error("Generation cancelled");
        throw std::runtime_error("llama.cpp DSpark prompt evaluation failed");
    }
    if (!common_speculative_process(session.speculative.get(), batch_prompt)) {
        if (abort_if_cancelled(&session)) throw std::runtime_error("Generation cancelled");
        throw std::runtime_error("llama.cpp DSpark rejected prompt features");
    }
    common_speculative_begin(session.speculative.get(), seq_id, prompt_tgt);
    result.prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - prefill_start).count();
    if (report_progress) {
        __android_log_print(
            ANDROID_LOG_INFO, "LlamaProbeNative",
            "event=dspark_prefill_complete prefill_tokens=%d elapsed_ms=%lld",
            static_cast<int>(prompt_tokens.size()) - 1,
            static_cast<long long>(result.prefill_ms));
    }

    llama_token id_last = prompt_tokens.back();
    int32_t n_past = static_cast<int32_t>(prompt_tokens.size()) - 1;
    llama_tokens draft;
    common_prompt_checkpoint checkpoint;
    while (result.generated_tokens < max_tokens && !result.complete) {
        if (abort_if_cancelled(&session)) throw std::runtime_error("Generation cancelled");
        if (draft.empty()) {
            checkpoint.update_pos(
                static_cast<int64_t>(prompt_tgt.size()),
                llama_memory_seq_pos_min(llama_get_memory(session.context), seq_id),
                llama_memory_seq_pos_max(llama_get_memory(session.context), seq_id));
            if (use_ckpt_dft) {
                checkpoint.update_dft(draft_context, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
            }

            const int32_t remaining = max_tokens - result.generated_tokens;
            const int32_t context_remaining =
                static_cast<int32_t>(llama_n_ctx(session.context)) - n_past - 2;
            const int32_t draft_limit = std::max(0, std::min({
                session.draft_n_max, remaining - 1, context_remaining,
            }));
            auto & draft_params = common_speculative_get_draft_params(session.speculative.get(), seq_id);
            draft_params.drafting = true;
            draft_params.n_max = draft_limit;
            draft_params.pos0 = n_past;
            draft_params.id_last = id_last;
            draft_params.prompt = &prompt_tgt;
            draft_params.result = &draft;
            draft_params.temp = 0.0f;
            common_speculative_draft(session.speculative.get());
            if (abort_if_cancelled(&session)) throw std::runtime_error("Generation cancelled");
            result.drafted_tokens += static_cast<int32_t>(draft.size());
            if (!draft.empty() && use_ckpt_tgt) {
                checkpoint.update_tgt(session.context, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY);
            }
        }

        if (use_ckpt_dft) {
            if (!checkpoint.load_dft(draft_context, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY)) {
                throw std::runtime_error("llama.cpp could not restore DSpark draft state");
            }
        }
        llama_memory_seq_rm(
            llama_get_memory(draft_context), seq_id, checkpoint.pos_max + 1, -1);

        common_batch batch_target(session.context);
        batch_target.add(id_last, n_past++, seq_id, true);
        for (size_t i = 0; i < draft.size(); ++i) {
            batch_target.add(draft[i], n_past + static_cast<llama_pos>(i), seq_id, true);
        }
        const int decode_status = llama_process(
            session.context, LLAMA_PROCESS_TYPE_DECODE, batch_target.get());
        if (decode_status != 0) {
            if (abort_if_cancelled(&session)) throw std::runtime_error("Generation cancelled");
            throw std::runtime_error("llama.cpp DSpark target evaluation failed");
        }
        if (!common_speculative_process(session.speculative.get(), batch_target)) {
            if (abort_if_cancelled(&session)) throw std::runtime_error("Generation cancelled");
            throw std::runtime_error("llama.cpp DSpark rejected target batch");
        }
        if (abort_if_cancelled(&session)) throw std::runtime_error("Generation cancelled");

        common_sampler_ptr sampler_checkpoint;
        if (use_ckpt_tgt) sampler_checkpoint.reset(common_sampler_clone(sampler.get()));
        const size_t n_draft = draft.size();
        const std::vector<llama_token> accepted =
            common_sampler_sample_and_accept_n(sampler.get(), session.context, draft, true);
        if (accepted.empty()) throw std::runtime_error("llama.cpp DSpark produced no target token");
        const size_t accepted_draft = accepted.size() - 1;

        if (use_ckpt_tgt && accepted_draft < n_draft) {
            draft = accepted;
            if (!checkpoint.load_tgt(session.context, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY)) {
                throw std::runtime_error("llama.cpp could not restore DSpark target state");
            }
            llama_memory_seq_rm(
                llama_get_memory(session.context), seq_id, checkpoint.pos_max + 1, -1);
            if (use_ckpt_dft) {
                if (!checkpoint.load_dft(draft_context, seq_id, LLAMA_STATE_SEQ_FLAGS_PARTIAL_ONLY)) {
                    throw std::runtime_error("llama.cpp could not restore DSpark draft state");
                }
                llama_memory_seq_rm(
                    llama_get_memory(draft_context), seq_id, checkpoint.pos_max + 1, -1);
            }
            prompt_tgt.resize(static_cast<size_t>(checkpoint.n_tokens));
            sampler = std::move(sampler_checkpoint);
            n_past = static_cast<int32_t>(prompt_tgt.size());
            continue;
        }

        result.accepted_draft_tokens += static_cast<int32_t>(accepted_draft);
        common_speculative_accept(
            session.speculative.get(), seq_id, static_cast<uint16_t>(accepted_draft));
        for (const llama_token token : accepted) {
            prompt_tgt.push_back(id_last);
            id_last = token;
            if (llama_vocab_is_eog(session.vocab, id_last)) {
                result.complete = true;
                break;
            }
            if (abort_if_cancelled(&session)) throw std::runtime_error("Generation cancelled");
            emit_token(id_last);
            ++result.generated_tokens;
            if (result.ttft_ms < 0) {
                result.ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - request_start).count();
            }
            if (result.generated_tokens >= max_tokens) break;
        }

        n_past += static_cast<int32_t>(accepted_draft);
        draft.clear();
        llama_memory_seq_rm(llama_get_memory(session.context), seq_id, n_past, -1);
        llama_memory_seq_rm(llama_get_memory(draft_context), seq_id, n_past, -1);
    }

    if (result.ttft_ms >= 0) {
        result.decode_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - request_start).count() - result.ttft_ms;
    }
    return result;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kernel_ai_debug_llamaprobe_LlamaCppSession_00024Companion_nativeOpen(
        JNIEnv * env, jobject, jstring model_path, jint context_size, jstring draft_model_path) {
    try {
        std::call_once(backend_once, [] { llama_backend_init(); });
        const std::string path = from_java(env, model_path, "modelPath");
        const std::string draft_path = draft_model_path == nullptr
            ? std::string{}
            : from_java(env, draft_model_path, "draftModelPath");
        const bool use_dspark = !draft_path.empty();

        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0; // CPU-only prototype; no GPU offload claim.
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(path.c_str(), model_params), &llama_model_free);
        if (model == nullptr) throw std::runtime_error("llama.cpp could not load the GGUF model");

        common_params spec_params;
        if (use_dspark) {
            spec_params.model.path = path;
            spec_params.n_ctx = context_size;
            spec_params.n_batch = context_size;
            spec_params.n_ubatch = 512;
            spec_params.n_gpu_layers = 0;
            spec_params.cpuparams.n_threads = 4;
            spec_params.cpuparams_batch.n_threads = 4;
            spec_params.speculative.types = {COMMON_SPECULATIVE_TYPE_DRAFT_DSPARK};
            spec_params.speculative.draft.n_max = 9;
            spec_params.speculative.draft.n_min = 0;
            spec_params.speculative.draft.n_gpu_layers = 0;
            spec_params.speculative.draft.mparams.path = draft_path;
            spec_params.speculative.draft.cpuparams.n_threads = 4;
            spec_params.speculative.draft.cpuparams_batch.n_threads = 4;
        }

        auto session = std::make_unique<ProbeSession>();
        session->vocab = llama_model_get_vocab(model.get());
        session->model = model.release();
        llama_context_params context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(context_size);
        context_params.n_batch = static_cast<uint32_t>(context_size);
        context_params.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
        context_params.n_threads_batch = context_params.n_threads;
        context_params.abort_callback = abort_if_cancelled;
        context_params.abort_callback_data = session.get();
        if (use_dspark) {
            spec_params.cpuparams.n_threads = static_cast<int32_t>(context_params.n_threads);
            spec_params.cpuparams_batch.n_threads = static_cast<int32_t>(context_params.n_threads);
            spec_params.speculative.draft.cpuparams.n_threads = static_cast<int32_t>(context_params.n_threads);
            spec_params.speculative.draft.cpuparams_batch.n_threads = static_cast<int32_t>(context_params.n_threads);
            session->draft_n_max = spec_params.speculative.draft.n_max;
            const auto output_limits = common_speculative_get_output_limits(
                context_params.n_batch, 1, session->draft_n_max);
            spec_params.n_outputs_max = output_limits.total;
            spec_params.n_outputs_max_per_seq = output_limits.per_seq;
            context_params.n_outputs_max = output_limits.total;
            context_params.n_outputs_max_per_seq = output_limits.per_seq;
            context_params.n_rs_seq = spec_params.speculative.need_n_rs_seq();
            context_params.n_ubatch = static_cast<uint32_t>(spec_params.n_ubatch);
        }
        __android_log_print(
            ANDROID_LOG_INFO, "LlamaProbeNative",
            "event=context_config n_ctx=%u n_batch=%u n_ubatch=%u n_threads=%d n_threads_batch=%d dspark=%d n_draft=%d n_rs_seq=%u",
            context_params.n_ctx, context_params.n_batch, context_params.n_ubatch,
            context_params.n_threads, context_params.n_threads_batch, use_dspark ? 1 : 0,
            session->draft_n_max, context_params.n_rs_seq);
        context_params.no_perf = true;
        session->context = llama_init_from_model(session->model, context_params);
        if (session->context == nullptr) throw std::runtime_error("llama.cpp could not create the CPU context");

        if (use_dspark) {
            common_params draft_params = common_base_params_to_speculative(spec_params);
            session->speculative_init = common_speculative_init_from_params(
                draft_params, session->model, session->context);
            if (session->speculative_init->model() == nullptr ||
                session->speculative_init->context() == nullptr) {
                throw std::runtime_error("llama.cpp could not initialize the DSpark GGUF");
            }
            llama_set_abort_callback(
                session->speculative_init->context(), abort_if_cancelled, session.get());
            draft_params.speculative.draft.ctx_tgt = session->context;
            draft_params.speculative.draft.ctx_dft = session->speculative_init->context();
            session->speculative.reset(common_speculative_init(draft_params.speculative, 1));
            if (!session->speculative) {
                throw std::runtime_error("llama.cpp rejected the LFM2.5 DSpark target/draft pair");
            }
            __android_log_print(
                ANDROID_LOG_INFO, "LlamaProbeNative",
                "event=dspark_ready draft_n_max=%d", session->draft_n_max);
        }
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_kernel_ai_debug_llamaprobe_LlamaCppSession_nativeGenerate(
        JNIEnv * env, jobject, jlong handle, jlong request_id, jstring prompt, jstring system_prompt,
        jstring schema_json, jobject token_observer, jint max_tokens, jboolean progress_logging) {
    try {
        ProbeSession * session = session_from(handle);
        std::lock_guard<std::mutex> guard(session->generation_mutex);
        if (max_tokens <= 0 || max_tokens > 2048) throw std::invalid_argument("maxTokens must be between 1 and 2048");

        const std::string user_prompt = from_java(env, prompt, "prompt");
        const std::string system = from_java(env, system_prompt, "systemPrompt");
        const std::string schema_text = from_java(env, schema_json, "schemaJson");
        if (user_prompt.size() > 64 * 1024 || system.size() > 16 * 1024 || schema_text.size() > 64 * 1024) {
            throw std::invalid_argument("Probe prompt or schema exceeds the bounded input size");
        }
        if (session->cancelled_request.load(std::memory_order_acquire) == request_id) {
            throw std::runtime_error("Generation cancelled");
        }
        session->active_request_id.store(request_id, std::memory_order_release);
        session->abort_logged.store(false, std::memory_order_relaxed);
        ActiveRequestGuard active_request_guard{*session};

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
        SamplerPtr sampler(nullptr, &llama_sampler_free);
        common_sampler_ptr dspark_sampler;
        if (session->speculative != nullptr) {
            common_params_sampling sampling_params;
            sampling_params.temp = 0.0f;
            sampling_params.samplers = {COMMON_SAMPLER_TYPE_TEMPERATURE};
            sampling_params.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, grammar);
            sampling_params.no_perf = true;
            dspark_sampler.reset(common_sampler_init(session->model, sampling_params));
            if (!dspark_sampler) throw std::runtime_error("Could not create DSpark constrained sampler");
        } else {
            sampler.reset(llama_sampler_chain_init(llama_sampler_chain_default_params()));
            if (!sampler) throw std::runtime_error("Could not create sampler chain");
            llama_sampler * grammar_sampler = llama_sampler_init_grammar(session->vocab, grammar.c_str(), "root");
            if (grammar_sampler == nullptr) throw std::invalid_argument("llama.cpp rejected the generated JSON grammar");
            llama_sampler_chain_add(sampler.get(), grammar_sampler);
            llama_sampler_chain_add(sampler.get(), llama_sampler_init_greedy());
        }

        const std::string formatted_prompt = format_model_prompt(session->model, system, user_prompt);
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
            throw std::runtime_error("llama.cpp failed to tokenize the LFM2.5 model chat template");
        }
        if (report_progress) {
            __android_log_print(
                ANDROID_LOG_INFO, "LlamaProbeNative", "event=tokenize_complete prompt_tokens=%d", prompt_token_count);
        }
        if (session->speculative != nullptr) {
            std::string dspark_output;
            std::array<char, 4096> token_buffer{};
            jmethodID on_token_bytes = nullptr;
            jbyteArray token_bytes = nullptr;
            if (token_observer != nullptr) {
                jclass observer_class = env->GetObjectClass(token_observer);
                if (observer_class == nullptr) return nullptr;
                on_token_bytes = env->GetMethodID(observer_class, "onTokenBytes", "([BI)V");
                if (on_token_bytes == nullptr) return nullptr;
                token_bytes = env->NewByteArray(static_cast<jsize>(token_buffer.size()));
                if (token_bytes == nullptr) return nullptr;
            }
            const auto emit_token = [&](llama_token token) {
                if (session->cancelled_request.load(std::memory_order_acquire) == request_id) {
                    throw std::runtime_error("Generation cancelled");
                }
                const int32_t piece_bytes = token_piece(
                    session->vocab, token, token_buffer.data(),
                    static_cast<int32_t>(token_buffer.size()));
                if (piece_bytes <= 0) return;
                dspark_output.append(token_buffer.data(), static_cast<size_t>(piece_bytes));
                if (token_observer != nullptr) {
                    env->SetByteArrayRegion(
                        token_bytes, 0, piece_bytes,
                        reinterpret_cast<const jbyte *>(token_buffer.data()));
                    if (env->ExceptionCheck()) throw std::runtime_error("Token observer failed");
                    env->CallVoidMethod(token_observer, on_token_bytes, token_bytes, piece_bytes);
                    if (env->ExceptionCheck()) throw std::runtime_error("Token observer failed");
                }
            };
            if (report_progress) {
                __android_log_print(ANDROID_LOG_INFO, "LlamaProbeNative", "event=dspark_generate_start");
            }
            const DsparkGenerationResult dspark_result = generate_dspark(
                *session, prompt_tokens, dspark_sampler, max_tokens,
                request_start, report_progress, emit_token);
            const int64_t total_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now() - request_start).count();
            if (report_progress) {
                __android_log_print(
                    ANDROID_LOG_INFO, "LlamaProbeNative",
                    "event=dspark_request_complete generated_tokens=%d complete=%d drafted_tokens=%d accepted_draft_tokens=%d ttft_ms=%lld prefill_ms=%lld decode_ms=%lld total_ms=%lld",
                    dspark_result.generated_tokens, dspark_result.complete ? 1 : 0,
                    dspark_result.drafted_tokens, dspark_result.accepted_draft_tokens,
                    static_cast<long long>(dspark_result.ttft_ms),
                    static_cast<long long>(dspark_result.prefill_ms),
                    static_cast<long long>(dspark_result.decode_ms), static_cast<long long>(total_ms));
            }
            const int32_t prefill_tokens = prompt_token_count - 1;
            const double prefill_tokens_per_second = dspark_result.prefill_ms > 0
                ? 1000.0 * static_cast<double>(prefill_tokens) /
                    static_cast<double>(dspark_result.prefill_ms)
                : 0.0;
            common_json result = {
                {"text", dspark_output},
                {"complete", dspark_result.complete},
                {"prompt_tokens", prompt_token_count},
                {"generated_tokens", dspark_result.generated_tokens},
                {"ttft_ms", std::max<int64_t>(dspark_result.ttft_ms, 0)},
                {"prefill_tokens", prefill_tokens},
                {"prefill_ms", dspark_result.prefill_ms},
                {"prefill_tokens_per_second", prefill_tokens_per_second},
                {"decode_ms", dspark_result.decode_ms},
                {"total_ms", total_ms},
                {"drafted_tokens", dspark_result.drafted_tokens},
                {"accepted_draft_tokens", dspark_result.accepted_draft_tokens},
            };
            return to_java_bytes(env, result.dump());
        }

        using BatchPtr = std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)>;
        BatchPtr batch(llama_batch_ext_init(session->context), &llama_batch_ext_free);
        if (!batch) throw std::runtime_error("Could not allocate llama.cpp batch");

        // Every request starts a new prompt at position zero; discard the prior sequence state.
        llama_memory_clear(llama_get_memory(session->context), false);

        batch_set_tokens(batch.get(), prompt_tokens.data(), prompt_token_count, 0);
        const auto prefill_start = std::chrono::steady_clock::now();
        if (report_progress) {
            __android_log_print(
                ANDROID_LOG_INFO, "LlamaProbeNative", "event=prefill_start prompt_tokens=%d", prompt_token_count);
        }
        std::atomic<bool> stop_prefill_cpu_samples{false};
        std::mutex prefill_cpu_sample_mutex;
        std::condition_variable prefill_cpu_sample_condition;
        std::thread prefill_cpu_sampler;
        if (report_progress) {
            prefill_cpu_sampler = std::thread(
                [&stop_prefill_cpu_samples, &prefill_cpu_sample_mutex, &prefill_cpu_sample_condition, prefill_start] {
                    std::int64_t previous_cpu_ns = process_cpu_time_ns();
                    int sample_index = 0;
                    std::unique_lock<std::mutex> wait_lock(prefill_cpu_sample_mutex);
                    while (!stop_prefill_cpu_samples.load()) {
                        const bool stopping = prefill_cpu_sample_condition.wait_for(
                            wait_lock,
                            std::chrono::seconds(5),
                            [&stop_prefill_cpu_samples] { return stop_prefill_cpu_samples.load(); });
                        if (stopping) break;
                        wait_lock.unlock();
                        const std::int64_t current_cpu_ns = process_cpu_time_ns();
                        const auto wall_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
                            std::chrono::steady_clock::now() - prefill_start).count();
                        const std::int64_t cpu_delta_ms =
                            previous_cpu_ns >= 0 && current_cpu_ns >= previous_cpu_ns
                            ? (current_cpu_ns - previous_cpu_ns) / 1000000LL
                            : -1;
                        const std::int64_t process_cpu_ms =
                            current_cpu_ns >= 0 ? current_cpu_ns / 1000000LL : -1;
                        __android_log_print(
                            ANDROID_LOG_INFO, "LlamaProbeNative",
                            "event=prefill_cpu_sample sample=%d wall_ms=%lld process_cpu_delta_ms=%lld process_cpu_total_ms=%lld",
                            ++sample_index, static_cast<long long>(wall_ms),
                            static_cast<long long>(cpu_delta_ms), static_cast<long long>(process_cpu_ms));
                        previous_cpu_ns = current_cpu_ns;
                        wait_lock.lock();
                    }
                });
        }
        const int prefill_status = llama_process(session->context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
        stop_prefill_cpu_samples.store(true);
        prefill_cpu_sample_condition.notify_all();
        if (prefill_cpu_sampler.joinable()) prefill_cpu_sampler.join();
        if (prefill_status != 0) {
            __android_log_print(
                ANDROID_LOG_WARN, "LlamaProbeNative",
                "event=prefill_failed request_id=%lld status=%d prompt_tokens=%d input_position_start=0 input_position_end_exclusive=%d n_ctx=%u",
                static_cast<long long>(request_id), prefill_status, prompt_token_count,
                prompt_token_count, llama_n_ctx(session->context));
            if (session->cancelled_request.load(std::memory_order_acquire) == request_id) {
                throw std::runtime_error("Generation cancelled");
            }
            throw std::runtime_error("llama.cpp prompt evaluation failed");
        }
        const int64_t prefill_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - prefill_start).count();
        if (report_progress) {
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
        jmethodID on_token_bytes = nullptr;
        jbyteArray token_bytes = nullptr;
        if (token_observer != nullptr) {
            jclass observer_class = env->GetObjectClass(token_observer);
            if (observer_class == nullptr) return nullptr;
            on_token_bytes = env->GetMethodID(observer_class, "onTokenBytes", "([BI)V");
            if (on_token_bytes == nullptr) return nullptr;
            token_bytes = env->NewByteArray(static_cast<jsize>(token_buffer.size()));
            if (token_bytes == nullptr) return nullptr;
        }
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
            if (token_observer != nullptr && piece_bytes > 0) {
                if (session->cancelled_request.load() == request_id) {
                    throw std::runtime_error("Generation cancelled");
                }
                env->SetByteArrayRegion(
                    token_bytes, 0, piece_bytes,
                    reinterpret_cast<const jbyte *>(token_buffer.data()));
                if (env->ExceptionCheck()) return nullptr;
                env->CallVoidMethod(token_observer, on_token_bytes, token_bytes, piece_bytes);
                if (env->ExceptionCheck()) return nullptr;
            }
            if (generated_tokens == 0) {
                first_token_at = std::chrono::steady_clock::now();
                ttft_ms = std::chrono::duration_cast<std::chrono::milliseconds>(first_token_at - request_start).count();
            }
            ++generated_tokens;
            if (report_progress) {
                __android_log_print(
                    ANDROID_LOG_INFO, "LlamaProbeNative", "event=token_piece_complete generated_tokens=%d",
                    generated_tokens);
            }
            if (session->cancelled_request.load() == request_id) {
                throw std::runtime_error("Generation cancelled");
            }
            if (generated_tokens == max_tokens) break;
            if (report_progress) {
                __android_log_print(
                    ANDROID_LOG_INFO, "LlamaProbeNative", "event=token_decode_start generated_tokens=%d",
                    generated_tokens);
            }
            batch_set_tokens(batch.get(), &next_token, 1, position++);
            const auto decode_start = report_progress
                ? std::chrono::steady_clock::now()
                : std::chrono::steady_clock::time_point{};
            const int decode_status = llama_process(session->context, LLAMA_PROCESS_TYPE_DECODE, batch.get());
            if (decode_status != 0) {
                if (session->cancelled_request.load(std::memory_order_acquire) == request_id) {
                    throw std::runtime_error("Generation cancelled");
                }
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
        const int64_t decode_ms = generated_tokens == 0
            ? 0
            : std::chrono::duration_cast<std::chrono::milliseconds>(done - first_token_at).count();
        if (report_progress) {
            __android_log_print(
                ANDROID_LOG_INFO, "LlamaProbeNative",
                "event=request_complete generated_tokens=%d complete=%d ttft_ms=%lld decode_ms=%lld total_ms=%lld",
                generated_tokens, completed ? 1 : 0, static_cast<long long>(ttft_ms),
                static_cast<long long>(decode_ms), static_cast<long long>(total_ms));
        }
        common_json result = {
            {"text", output},
            {"complete", completed},
            {"prompt_tokens", prompt_token_count},
            {"generated_tokens", generated_tokens},
            {"prefill_tokens", prompt_token_count},
            {"prefill_ms", prefill_ms},
            {"prefill_tokens_per_second", prefill_ms > 0
                ? 1000.0 * static_cast<double>(prompt_token_count) / static_cast<double>(prefill_ms)
                : 0.0},
            {"drafted_tokens", 0},
            {"accepted_draft_tokens", 0},
        };
        const std::string json_result = result.dump();
        return to_java_bytes(env, json_result);
    } catch (const std::exception & error) {
        if (env->ExceptionCheck()) return nullptr;
        throw_java(env, "java/lang/IllegalStateException", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kernel_ai_debug_llamaprobe_LlamaCppSession_nativeCancel(
        JNIEnv * env, jobject, jlong handle, jlong request_id) {
    try {
        session_from(handle)->cancelled_request.store(request_id, std::memory_order_release);
    } catch (const std::exception & error) {
        throw_java(env, "java/lang/IllegalStateException", error.what());
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
