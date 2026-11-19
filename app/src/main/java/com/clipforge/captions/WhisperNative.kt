package com.clipforge.captions

/** JNI surface of libclipforge_whisper (src/main/cpp/whisper_jni.cpp). */
object WhisperNative {
    init {
        System.loadLibrary("clipforge_whisper")
    }

    @JvmStatic external fun nativeInit(modelPath: String): Long
    @JvmStatic external fun nativeFree(ctx: Long)
    @JvmStatic external fun nativeAbort()
    /** @return UTF-8 JSON {"lang":..,"segments":[{"t","t0","t1","p","ns"}]} or null on failure. */
    @JvmStatic external fun nativeTranscribe(ctx: Long, pcm16kMono: FloatArray, threads: Int, language: String): ByteArray?
}
