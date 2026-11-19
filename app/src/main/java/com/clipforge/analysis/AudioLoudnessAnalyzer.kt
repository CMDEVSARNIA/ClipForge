package com.clipforge.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Decodes the audio track to PCM with MediaCodec and returns RMS loudness (dBFS) per 0.5 s window.
 * Streams buffer by buffer, so memory stays flat for hour-long files (output is ~7200 floats/hour).
 * Returns an empty array when there is no decodable audio.
 */
class AudioLoudnessAnalyzer(private val context: Context) {

    suspend fun analyze(uri: Uri, durationMs: Long, onProgress: (Float) -> Unit): FloatArray = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext FloatArray(0)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var pcmFloat = false

            val c = MediaCodec.createDecoderByType(mime).also { codec = it }
            c.configure(format, null, null, 0)
            c.start()

            val expectedWindows = (durationMs / 1000.0 / LoudnessMath.WINDOW_S).toInt() + 4
            var out = FloatArray(expectedWindows)
            var windows = 0
            var acc = 0.0
            var accCount = 0L
            var samplesPerWindow = (sampleRate * LoudnessMath.WINDOW_S).toLong() * channels
            val shorts = ShortArray(16_384)
            val floats = FloatArray(16_384)

            fun pushWindow() {
                if (windows == out.size) out = out.copyOf(out.size * 2)
                out[windows++] = LoudnessMath.rmsDb(acc, accCount)
                acc = 0.0
                accCount = 0
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var lastReported = -1
            while (!outputDone) {
                ensureActive()
                if (!inputDone) {
                    val inIdx = c.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = c.getInputBuffer(inIdx)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = c.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    samplesPerWindow = (sampleRate * LoudnessMath.WINDOW_S).toLong() * channels
                } else if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = c.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val ordered = buf.order(ByteOrder.nativeOrder())
                        if (pcmFloat) {
                            val fb = ordered.asFloatBuffer()
                            while (fb.hasRemaining()) {
                                val n = minOf(fb.remaining(), floats.size)
                                fb.get(floats, 0, n)
                                for (i in 0 until n) {
                                    val v = floats[i].toDouble()
                                    acc += v * v
                                    if (++accCount >= samplesPerWindow) pushWindow()
                                }
                            }
                        } else {
                            val sb = ordered.asShortBuffer()
                            while (sb.hasRemaining()) {
                                val n = minOf(sb.remaining(), shorts.size)
                                sb.get(shorts, 0, n)
                                for (i in 0 until n) {
                                    val v = shorts[i] / 32768.0
                                    acc += v * v
                                    if (++accCount >= samplesPerWindow) pushWindow()
                                }
                            }
                        }
                        if (durationMs > 0) {
                            val pct = (info.presentationTimeUs / 1000.0 / durationMs * 100).toInt().coerceIn(0, 100)
                            if (pct != lastReported) { lastReported = pct; onProgress(pct / 100f) }
                        }
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (accCount > 0) pushWindow()
            out.copyOf(windows)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Undecodable audio (rare codecs, DRM): moment finding continues on visuals alone.
            FloatArray(0)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
