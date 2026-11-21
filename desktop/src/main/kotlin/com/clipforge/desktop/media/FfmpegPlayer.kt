package com.clipforge.desktop.media

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.clipforge.media.VideoInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Preview frame size: fits the display aspect inside [maxW]×[maxH], even dimensions (ffmpeg scale needs them). */
object PreviewSize {
    fun fit(displayW: Int, displayH: Int, maxW: Int = 960, maxH: Int = 540): Pair<Int, Int> {
        if (displayW <= 0 || displayH <= 0) return maxW to maxH
        val scale = minOf(maxW.toDouble() / displayW, maxH.toDouble() / displayH, 1.0)
        fun even(v: Double) = (v.toInt() / 2 * 2).coerceAtLeast(2)
        return even(displayW * scale) to even(displayH * scale)
    }

    /** Frames that are already later than this behind the clock are dropped instead of shown. */
    fun shouldDrop(ptsMs: Long, clockMs: Long, frameMs: Long): Boolean = ptsMs + frameMs < clockMs
}

/**
 * A lightweight preview player: one ffmpeg process decodes scaled BGRA frames at [FPS], another decodes
 * 48 kHz stereo PCM for javax.sound. Video follows the audio clock (wall clock when there's no audio
 * or no sound device). Paused seeks decode a single frame.
 */
class FfmpegPlayer(
    private val tools: MediaTools,
    private val file: File,
    val info: VideoInfo,
    private val scope: CoroutineScope,
) {
    val size: Pair<Int, Int> = PreviewSize.fit(info.displayWidth, info.displayHeight)
    private val w get() = size.first
    private val h get() = size.second

    private val _frame = MutableStateFlow<ImageBitmap?>(null)
    val frame: StateFlow<ImageBitmap?> = _frame.asStateFlow()
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var playJob: Job? = null
    private val seeks = Channel<Long>(Channel.CONFLATED)

    init {
        // Paused seeks: only the newest request is decoded, so dragging the timeline stays responsive.
        scope.launch(Dispatchers.IO) {
            for (ms in seeks) {
                runCatching { _frame.value = toBitmap(tools.frameBgra(file, ms, w, h)) }
                    .onFailure { _error.value = it.message }
            }
        }
        seek(0)
    }

    fun togglePlay() = if (_playing.value) pause() else play()

    fun play() {
        if (_playing.value) return
        if (_position.value >= info.durationMs - 200) _position.value = 0
        _playing.value = true
        val start = _position.value
        playJob = scope.launch(Dispatchers.IO) { runPlayback(start) }
    }

    fun pause() {
        _playing.value = false
        playJob?.cancel()
        playJob = null
    }

    /** Jump to [ms]; keeps playing if it was playing. */
    fun seek(ms: Long) {
        val t = ms.coerceIn(0, (info.durationMs - 1).coerceAtLeast(0))
        val wasPlaying = _playing.value
        if (wasPlaying) {
            val old = playJob
            _playing.value = false
            scope.launch {
                old?.cancelAndJoin()
                _position.value = t
                play()
            }
        } else {
            _position.value = t
            seeks.trySend(t)
        }
    }

    fun skip(deltaMs: Long) = seek(_position.value + deltaMs)

    fun close() {
        pause()
        seeks.close()
    }

    private suspend fun runPlayback(startMs: Long) {
        val frameBytes = w * h * 4
        val frameMs = 1000L / FPS
        val video = tools.ff.start(
            listOf("-ss", Ffmpeg.seconds(startMs), "-i", file.path, "-an", "-sn",
                "-vf", "fps=$FPS,scale=$w:$h", "-f", "rawvideo", "-pix_fmt", "bgra", "-")
        )
        val audio = if (info.hasAudio) AudioOut.open(tools.ff, file, startMs) else null
        val t0 = System.nanoTime()
        val clock: () -> Long = audio?.let { a -> { a.positionMs() } } ?: { (System.nanoTime() - t0) / 1_000_000 }
        val input = DataInputStream(video.inputStream.buffered(frameBytes * 2))
        val buf = ByteArray(frameBytes)
        var index = 0L
        try {
            while (currentCoroutineContext().isActive && _playing.value) {
                try {
                    runInterruptible { input.readFully(buf) }
                } catch (_: EOFException) {
                    _position.value = info.durationMs
                    _playing.value = false
                    break
                }
                val pts = index * 1000 / FPS
                index++
                if (PreviewSize.shouldDrop(pts, clock(), frameMs)) continue
                val wait = pts - clock()
                if (wait > 0) delay(wait)
                _frame.value = toBitmap(buf)
                _position.value = (startMs + pts).coerceAtMost(info.durationMs)
            }
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.CancellationException) _error.value = e.message
        } finally {
            video.destroyForcibly()
            audio?.close()
        }
    }

    private fun toBitmap(bgra: ByteArray): ImageBitmap =
        Image.makeRaster(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bgra.copyOf(w * h * 4), w * 4)
            .toComposeImageBitmap()

    companion object {
        const val FPS = 30
    }
}

/** PCM from ffmpeg into the default sound device; the line's play position is the playback clock. */
class AudioOut private constructor(private val process: Process, private val line: SourceDataLine) {
    @Volatile private var closed = false
    private val pump = Thread {
        val buf = ByteArray(8192)
        val input = process.inputStream
        try {
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) break
                line.write(buf, 0, n - n % 4)
            }
        } catch (_: Exception) {
        }
    }.apply { isDaemon = true; name = "clipforge-audio" }

    init {
        line.start()
        pump.start()
    }

    fun positionMs(): Long = line.microsecondPosition / 1000

    fun close() {
        closed = true
        process.destroyForcibly()
        runCatching { line.stop(); line.flush(); line.close() }
    }

    companion object {
        private val FORMAT = AudioFormat(48_000f, 16, 2, true, false)

        /** Null when there's no usable sound device; playback then follows the wall clock. */
        fun open(ff: Ffmpeg, file: File, startMs: Long): AudioOut? = runCatching {
            val line = AudioSystem.getSourceDataLine(FORMAT)
            line.open(FORMAT, 48_000 * 4 / 5) // ~200 ms buffer
            val p = ff.start(listOf("-ss", Ffmpeg.seconds(startMs), "-i", file.path, "-vn", "-sn",
                "-ac", "2", "-ar", "48000", "-f", "s16le", "-"))
            AudioOut(p, line)
        }.getOrNull()
    }
}
