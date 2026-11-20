package com.clipforge.captions

import android.os.Build
import java.io.File

/** Decides whether this CPU can run the ARMv8.2 (dot-product + FP16) whisper build. */
object CpuFeatures {
    /** @param cpuinfo contents of /proc/cpuinfo. */
    fun supportsFastWhisper(cpuinfo: String, abis: List<String>): Boolean {
        if ("arm64-v8a" !in abis) return false
        // A big.LITTLE CPU lists features per core; require both features on every core.
        val perCore = cpuinfo.lineSequence()
            .filter { it.trimStart().startsWith("Features", ignoreCase = true) }
            .map { it.substringAfter(':').trim().split(Regex("\\s+")).toSet() }
            .toList()
        return perCore.isNotEmpty() && perCore.all { f -> "asimddp" in f && ("asimdhp" in f || "fphp" in f) }
    }

    fun fastOnThisDevice(): Boolean {
        val cpuinfo = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
        return supportsFastWhisper(cpuinfo, Build.SUPPORTED_ABIS.toList())
    }

    /** Shown in Settings without loading the native library. */
    fun variantForThisDevice(): String = if (fastOnThisDevice()) "fast" else "compatible"
}

/** JNI surface of libclipforge_whisper[_fast] (src/main/cpp/whisper_jni.cpp). */
object WhisperNative {
    /** "fast" when the ARMv8.2 build loaded, "compatible" otherwise. */
    val variant: String

    init {
        variant = if (CpuFeatures.fastOnThisDevice() && runCatching { System.loadLibrary("clipforge_whisper_fast") }.isSuccess) {
            "fast"
        } else {
            System.loadLibrary("clipforge_whisper")
            "compatible"
        }
    }

    @JvmStatic external fun nativeInit(modelPath: String): Long
    @JvmStatic external fun nativeFree(ctx: Long)
    @JvmStatic external fun nativeAbort()
    /** @return UTF-8 JSON {"lang":..,"segments":[{"t","t0","t1","p","ns"}]} or null on failure. */
    @JvmStatic external fun nativeTranscribe(ctx: Long, pcm16kMono: FloatArray, threads: Int, language: String): ByteArray?
}
