package com.clipforge.export

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Applies the clip's fade-in (first segment) and fade-out (last segment) as a gain ramp on PCM.
 * [itemDurationUs] is this media item's duration so the fade-out lands on its last samples.
 */
@UnstableApi
class FadeAudioProcessor(
    private val itemDurationUs: Long,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
) : BaseAudioProcessor() {

    private var frame = 0L
    private var totalFrames = 0L
    private var fadeInFrames = 0L
    private var fadeOutFrames = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (fadeInUs <= 0 && fadeOutUs <= 0) return AudioProcessor.AudioFormat.NOT_SET // inactive
        val sr = inputAudioFormat.sampleRate.toLong()
        totalFrames = itemDurationUs * sr / 1_000_000
        fadeInFrames = fadeInUs * sr / 1_000_000
        fadeOutFrames = fadeOutUs * sr / 1_000_000
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val fmt = inputAudioFormat
        val channels = fmt.channelCount
        val out = replaceOutputBuffer(remaining)
        val inBuf = inputBuffer.order(ByteOrder.nativeOrder())
        if (fmt.encoding == C.ENCODING_PCM_16BIT) {
            while (inBuf.remaining() >= 2 * channels) {
                val g = ExportMath.fadeGain(frame, totalFrames, fadeInFrames, fadeOutFrames)
                for (c in 0 until channels) out.putShort((inBuf.short * g).toInt().coerceIn(-32768, 32767).toShort())
                frame++
            }
        } else {
            while (inBuf.remaining() >= 4 * channels) {
                val g = ExportMath.fadeGain(frame, totalFrames, fadeInFrames, fadeOutFrames)
                for (c in 0 until channels) out.putFloat(inBuf.float * g)
                frame++
            }
        }
        // Drop any trailing partial frame bytes (shouldn't happen with well-formed PCM).
        inBuf.position(inBuf.limit())
        out.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        frame = 0
    }

    override fun onReset() {
        frame = 0
    }
}
