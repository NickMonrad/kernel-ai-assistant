#include <phonemis/base/pipeline.h>
#include <phonemis/utils/conversions.h>

#include <jni.h>

#include <cstdint>
#include <stdexcept>
#include <string>
#include <string_view>
#include <utility>

namespace {

struct NativePipeline {
  explicit NativePipeline(phonemis::Config config) : pipeline(config) {}
  phonemis::Pipeline pipeline;
};

class Utf16Chars {
 public:
  Utf16Chars(JNIEnv* env, jstring string) : env_(env), string_(string), chars_(env->GetStringChars(string, nullptr)) {
    if (chars_ == nullptr) throw std::runtime_error("Unable to read Java string");
  }
  ~Utf16Chars() { env_->ReleaseStringChars(string_, chars_); }
  const jchar* get() const { return chars_; }

 private:
  JNIEnv* env_;
  jstring string_;
  const jchar* chars_;
};

std::string to_utf8(JNIEnv* env, jstring text) {
  Utf16Chars chars(env, text);
  const jsize length = env->GetStringLength(text);
  std::string output;
  output.reserve(static_cast<std::size_t>(length));
  for (jsize i = 0; i < length; ++i) {
    uint32_t codepoint = chars.get()[i];
    if (codepoint >= 0xd800 && codepoint <= 0xdbff) {
      if (++i >= length || chars.get()[i] < 0xdc00 || chars.get()[i] > 0xdfff) {
        throw std::invalid_argument("Input contains an unpaired UTF-16 surrogate");
      }
      codepoint = 0x10000 + ((codepoint - 0xd800) << 10) + (chars.get()[i] - 0xdc00);
    } else if (codepoint >= 0xdc00 && codepoint <= 0xdfff) {
      throw std::invalid_argument("Input contains an unpaired UTF-16 surrogate");
    }
    if (codepoint <= 0x7f) {
      output.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ff) {
      output.push_back(static_cast<char>(0xc0 | (codepoint >> 6)));
      output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else if (codepoint <= 0xffff) {
      output.push_back(static_cast<char>(0xe0 | (codepoint >> 12)));
      output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
      output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    } else {
      output.push_back(static_cast<char>(0xf0 | (codepoint >> 18)));
      output.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3f)));
      output.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3f)));
      output.push_back(static_cast<char>(0x80 | (codepoint & 0x3f)));
    }
  }
  return output;
}

jstring to_java_string(JNIEnv* env, std::u32string_view text) {
  std::vector<jchar> utf16;
  utf16.reserve(text.size());
  for (char32_t codepoint : text) {
    if (codepoint <= 0xffff) {
      utf16.push_back(static_cast<jchar>(codepoint));
    } else {
      const uint32_t supplementary = static_cast<uint32_t>(codepoint) - 0x10000;
      utf16.push_back(static_cast<jchar>(0xd800 + (supplementary >> 10)));
      utf16.push_back(static_cast<jchar>(0xdc00 + (supplementary & 0x3ff)));
    }
  }
  const jchar empty = 0;
  return env->NewString(utf16.empty() ? &empty : utf16.data(), static_cast<jsize>(utf16.size()));
}

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
  jclass exception_class = env->FindClass(class_name);
  if (exception_class != nullptr) {
    env->ThrowNew(exception_class, message.c_str());
    env->DeleteLocalRef(exception_class);
  }
}

NativePipeline* from_handle(jlong handle) {
  if (handle == 0) throw std::invalid_argument("Phonemizer is closed");
  return reinterpret_cast<NativePipeline*>(static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_kernel_ai_core_voice_kokoro_NativeKokoroPhonemizer_nativeCreate(
    JNIEnv* env, jobject, jstring locale, jstring lexicon_path, jstring tagger_path) {
  try {
    if (locale == nullptr || lexicon_path == nullptr || tagger_path == nullptr) {
      throw std::invalid_argument("Locale and phonemizer data paths are required");
    }
    const char* locale_chars = env->GetStringUTFChars(locale, nullptr);
    if (locale_chars == nullptr) return 0;
    phonemis::Config config;
    config.lang = locale_chars;
    config.phonemizer.lang = config.lang;
    env->ReleaseStringUTFChars(locale, locale_chars);
    if (config.lang != "en-us" && config.lang != "en-gb") {
      throw std::invalid_argument("Supported locales are en-us and en-gb");
    }

    const char* lexicon_chars = env->GetStringUTFChars(lexicon_path, nullptr);
    if (lexicon_chars == nullptr) return 0;
    config.phonemizer.lexicon_filepath = lexicon_chars;
    env->ReleaseStringUTFChars(lexicon_path, lexicon_chars);
    const char* tagger_chars = env->GetStringUTFChars(tagger_path, nullptr);
    if (tagger_chars == nullptr) return 0;
    config.tagger = phonemis::tagger::Config{.data_filepath = tagger_chars};
    env->ReleaseStringUTFChars(tagger_path, tagger_chars);

    return static_cast<jlong>(reinterpret_cast<intptr_t>(new NativePipeline(std::move(config))));
  } catch (const std::exception& error) {
    throw_java(env, "java/lang/IllegalArgumentException", error.what());
    return 0;
  } catch (...) {
    throw_java(env, "java/lang/RuntimeException", "Unknown native phonemizer initialization error");
    return 0;
  }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_kernel_ai_core_voice_kokoro_NativeKokoroPhonemizer_nativePhonemize(
    JNIEnv* env, jobject, jlong handle, jstring input) {
  try {
    if (input == nullptr) throw std::invalid_argument("Input text must not be null");
    const std::string input_utf8 = to_utf8(env, input);
    const auto result = from_handle(handle)->pipeline.phonemize_words(input_utf8);
    return to_java_string(env, result.phonemes);
  } catch (const std::exception& error) {
    throw_java(env, "java/lang/IllegalArgumentException", error.what());
    return nullptr;
  } catch (...) {
    throw_java(env, "java/lang/RuntimeException", "Unknown native phonemization error");
    return nullptr;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_kernel_ai_core_voice_kokoro_NativeKokoroPhonemizer_nativeClose(
    JNIEnv* env, jobject, jlong handle) {
  try {
    delete from_handle(handle);
  } catch (const std::exception& error) {
    throw_java(env, "java/lang/IllegalStateException", error.what());
  } catch (...) {
    throw_java(env, "java/lang/RuntimeException", "Unknown native phonemizer close error");
  }
}

