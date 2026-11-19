package com.clipforge.captions

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** whisper.cpp ggml models, quantized (q5_1) to keep the download small. Hashes from Hugging Face. */
enum class SpeechModel(val label: String, val file: String, val bytes: Long, val sha256: String) {
    TINY("Tiny · 31 MB · fastest", "ggml-tiny-q5_1.bin", 32_152_673, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"),
    BASE("Base · 57 MB · more accurate", "ggml-base-q5_1.bin", 59_707_625, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
    ;

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$file"

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: TINY
    }
}

class SpeechModelStore(private val context: Context) {
    private val dir = File(context.filesDir, "models")
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun file(m: SpeechModel) = File(dir, m.file)
    fun isReady(m: SpeechModel) = file(m).length() == m.bytes

    fun delete(m: SpeechModel) {
        file(m).delete()
    }

    /** Downloads (if needed) and verifies the model's SHA-256. */
    suspend fun ensure(m: SpeechModel, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = file(m)
        if (isReady(m)) return@withContext target
        dir.mkdirs()
        val part = File(dir, m.file + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(m.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("Model download failed (HTTP ${r.code}).")
            val total = r.body.contentLength().takeIf { it > 0 } ?: m.bytes
            r.body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val k = input.read(buf)
                        if (k < 0) break
                        out.write(buf, 0, k)
                        digest.update(buf, 0, k)
                        done += k
                        onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != m.sha256) {
            part.delete()
            throw IllegalStateException("Model checksum mismatch; download discarded.")
        }
        if (!part.renameTo(target)) throw IllegalStateException("Couldn't save the model.")
        target
    }
}

/** One loaded whisper context. Not thread-safe: use from one coroutine at a time. */
class WhisperEngine private constructor(private var ctx: Long) : AutoCloseable {
    fun transcribe(pcm16k: FloatArray, language: String): WhisperResult? {
        check(ctx != 0L) { "closed" }
        val threads = Runtime.getRuntime().availableProcessors().let { (it / 2).coerceIn(2, 4) }
        val bytes = WhisperNative.nativeTranscribe(ctx, pcm16k, threads, language) ?: return null
        return CaptionMath.parseWhisper(bytes)
    }

    fun abort() = WhisperNative.nativeAbort()

    override fun close() {
        if (ctx != 0L) WhisperNative.nativeFree(ctx)
        ctx = 0
    }

    companion object {
        fun load(model: File): WhisperEngine {
            val ctx = WhisperNative.nativeInit(model.absolutePath)
            if (ctx == 0L) throw IllegalStateException("Couldn't load the speech model.")
            return WhisperEngine(ctx)
        }
    }
}
