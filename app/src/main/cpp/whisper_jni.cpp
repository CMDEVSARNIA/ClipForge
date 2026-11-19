// JNI bridge: load a ggml whisper model, transcribe 16 kHz mono float PCM, and return word-level
// segments as UTF-8 JSON bytes (decoded in Kotlin, since whisper can emit partial UTF-8 that
// NewStringUTF would reject).
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <string>
#include "whisper_core.h"

#define LOG_TAG "ClipForgeWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static std::atomic<bool> g_abort{false};

static bool abort_cb(void *) { return g_abort.load(); }

extern "C" JNIEXPORT jlong JNICALL
Java_com_clipforge_captions_WhisperNative_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    const char *path = env->GetStringUTFChars(modelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    return (jlong) ctx;
}

extern "C" JNIEXPORT void JNICALL
Java_com_clipforge_captions_WhisperNative_nativeFree(JNIEnv *, jclass, jlong ctx) {
    if (ctx) whisper_free((whisper_context *) ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_clipforge_captions_WhisperNative_nativeAbort(JNIEnv *, jclass) {
    g_abort.store(true);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_clipforge_captions_WhisperNative_nativeTranscribe(
        JNIEnv *env, jclass, jlong ctxPtr, jfloatArray pcm, jint threads, jstring language) {
    auto *ctx = (whisper_context *) ctxPtr;
    if (!ctx) return nullptr;
    g_abort.store(false);

    const char *lang = env->GetStringUTFChars(language, nullptr);
    std::string langStr(lang);
    env->ReleaseStringUTFChars(language, lang);

    jsize n = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    std::string json = clipforge_transcribe_json(ctx, samples, n, threads, langStr, abort_cb);
    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    if (json.empty()) {
        LOGI("whisper_full failed");
        return nullptr;
    }

    jbyteArray out = env->NewByteArray((jsize) json.size());
    env->SetByteArrayRegion(out, 0, (jsize) json.size(), (const jbyte *) json.data());
    return out;
}
