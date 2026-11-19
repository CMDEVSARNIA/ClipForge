package com.clipforge.captions

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

/** Decodes [startMs, endMs) of the audio track to 16 kHz mono float PCM for whisper. */
class PcmExtractor(private val context: Context) {

    suspend fun extract16kMono(uri: Uri, startMs: Long, endMs: Long): FloatArray? = withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return@withContext null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            c.configure(fmt, null, null, 0)
            c.start()

            var mono = FloatArray(((endMs - startMs) / 1000.0 * rate).toInt() + rate)
            var n = 0
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var outDone = false
            val endUs = endMs * 1000
            val startUs = startMs * 1000
            while (!outDone) {
                ensureActive()
                if (!inDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val sz = ex.readSampleData(c.getInputBuffer(i)!!, 0)
                        if (sz < 0 || ex.sampleTime > endUs + 200_000) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                        } else {
                            c.queueInputBuffer(i, 0, sz, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE)) rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    isFloat = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val buf = c.getOutputBuffer(o)!!.apply { position(info.offset); limit(info.offset + info.size) }.order(ByteOrder.nativeOrder())
                        val frames = info.size / ((if (isFloat) 4 else 2) * channels)
                        val us0 = info.presentationTimeUs
                        for (f in 0 until frames) {
                            var sum = 0f
                            for (ch in 0 until channels) sum += if (isFloat) buf.float else buf.short / 32768f
                            val tUs = us0 + f * 1_000_000L / rate
                            if (tUs < startUs || tUs >= endUs) continue
                            if (n == mono.size) mono = mono.copyOf(mono.size * 2)
                            mono[n++] = sum / channels
                        }
                    }
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                }
            }
            CaptionMath.resample(mono.copyOf(n), rate, 16_000)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }
}
