#include <jni.h>
#include <android/log.h>
#include <whisper.h>

#include <algorithm>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr const char *kTag = "PocketAgentWhisper";

void throwJava(JNIEnv *env, const char *className, const char *message) {
    jclass errorClass = env->FindClass(className);
    if (errorClass != nullptr) env->ThrowNew(errorClass, message);
}

whisper_context *contextFrom(jlong handle) {
    return reinterpret_cast<whisper_context *>(static_cast<intptr_t>(handle));
}

jstring newJavaString(JNIEnv *env, const std::string &utf8) {
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    size_t index = 0;
    while (index < utf8.size()) {
        const auto first = static_cast<unsigned char>(utf8[index]);
        uint32_t codepoint = 0xfffd;
        size_t length = 1;
        if (first <= 0x7f) {
            codepoint = first;
        } else if (first >= 0xc2 && first <= 0xdf && index + 1 < utf8.size()) {
            const auto b1 = static_cast<unsigned char>(utf8[index + 1]);
            if ((b1 & 0xc0) == 0x80) {
                codepoint = ((first & 0x1f) << 6) | (b1 & 0x3f);
                length = 2;
            }
        } else if (first >= 0xe0 && first <= 0xef && index + 2 < utf8.size()) {
            const auto b1 = static_cast<unsigned char>(utf8[index + 1]);
            const auto b2 = static_cast<unsigned char>(utf8[index + 2]);
            if ((b1 & 0xc0) == 0x80 && (b2 & 0xc0) == 0x80 &&
                !(first == 0xe0 && b1 < 0xa0) && !(first == 0xed && b1 >= 0xa0)) {
                codepoint = ((first & 0x0f) << 12) | ((b1 & 0x3f) << 6) | (b2 & 0x3f);
                length = 3;
            }
        } else if (first >= 0xf0 && first <= 0xf4 && index + 3 < utf8.size()) {
            const auto b1 = static_cast<unsigned char>(utf8[index + 1]);
            const auto b2 = static_cast<unsigned char>(utf8[index + 2]);
            const auto b3 = static_cast<unsigned char>(utf8[index + 3]);
            if ((b1 & 0xc0) == 0x80 && (b2 & 0xc0) == 0x80 && (b3 & 0xc0) == 0x80 &&
                !(first == 0xf0 && b1 < 0x90) && !(first == 0xf4 && b1 >= 0x90)) {
                codepoint = ((first & 0x07) << 18) | ((b1 & 0x3f) << 12) |
                            ((b2 & 0x3f) << 6) | (b3 & 0x3f);
                length = 4;
            }
        }
        if (codepoint <= 0xffff) {
            utf16.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xd800 + (codepoint >> 10)));
            utf16.push_back(static_cast<jchar>(0xdc00 + (codepoint & 0x3ff)));
        }
        index += length;
    }
    return env->NewString(utf16.empty() ? nullptr : utf16.data(), static_cast<jsize>(utf16.size()));
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_pocketagent_WhisperNative_loadModel(JNIEnv *env, jobject, jstring modelPath) {
    if (modelPath == nullptr) {
        throwJava(env, "java/lang/IllegalArgumentException", "A local Whisper model file is required.");
        return 0;
    }
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    if (path == nullptr) return 0;

    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *context = whisper_init_from_file_with_params(path, params);
    env->ReleaseStringUTFChars(modelPath, path);
    if (context == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kTag, "Could not load the selected ggml Whisper model");
        throwJava(env, "java/lang/IllegalArgumentException", "The selected file is not a readable whisper.cpp ggml model.");
        return 0;
    }
    return static_cast<jlong>(reinterpret_cast<intptr_t>(context));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_pocketagent_WhisperNative_transcribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray audioSamples, jint maxChars) {
    whisper_context *context = contextFrom(handle);
    if (context == nullptr || audioSamples == nullptr || maxChars <= 0) {
        throwJava(env, "java/lang/IllegalArgumentException", "Invalid transcription input.");
        return nullptr;
    }

    const jsize sampleCount = env->GetArrayLength(audioSamples);
    if (sampleCount <= 0) {
        throwJava(env, "java/lang/IllegalArgumentException", "The WAV file contains no audio samples.");
        return nullptr;
    }

    jboolean isCopy = JNI_FALSE;
    jfloat *samples = env->GetFloatArrayElements(audioSamples, &isCopy);
    if (samples == nullptr) return nullptr;

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    const unsigned int availableThreads = std::thread::hardware_concurrency();
    params.n_threads = static_cast<int>(std::max(1u, std::min(4u, availableThreads == 0 ? 2u : availableThreads)));
    params.no_context = true;
    params.language = "auto";
    params.translate = false;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;

    const int result = whisper_full(context, params, samples, sampleCount);
    env->ReleaseFloatArrayElements(audioSamples, samples, JNI_ABORT);
    if (result != 0) {
        throwJava(env, "java/lang/IllegalStateException", "Whisper could not transcribe this WAV file.");
        return nullptr;
    }

    std::string transcript;
    const int segmentCount = whisper_full_n_segments(context);
    for (int index = 0; index < segmentCount && transcript.size() < static_cast<size_t>(maxChars); ++index) {
        const char *segment = whisper_full_get_segment_text(context, index);
        if (segment == nullptr) continue;
        const size_t segmentLength = std::char_traits<char>::length(segment);
        size_t take = std::min(static_cast<size_t>(maxChars) - transcript.size(), segmentLength);
        if (take < segmentLength) {
            while (take > 0 && (static_cast<unsigned char>(segment[take]) & 0xc0) == 0x80) --take;
        }
        if (take == 0) break;
        transcript.append(segment, take);
    }
    return newJavaString(env, transcript);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_pocketagent_WhisperNative_release(JNIEnv *, jobject, jlong handle) {
    whisper_context *context = contextFrom(handle);
    if (context != nullptr) whisper_free(context);
}
