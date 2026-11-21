package com.clipforge.desktop.media

import com.clipforge.media.VideoInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.math.roundToInt

/** Turns `ffprobe -print_format json -show_format -show_streams` output into the shared [VideoInfo]. */
object ProbeParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun args(file: File) = listOf("-v", "error", "-print_format", "json", "-show_format", "-show_streams", file.path)

    fun parse(text: String, displayName: String?, sizeBytes: Long?): VideoInfo {
        val root = json.parseToJsonElement(text).jsonObject
        val streams = (root["streams"] as? JsonArray)?.map { it.jsonObject }.orEmpty()
        val video = streams.firstOrNull { it.str("codec_type") == "video" && it.int("disposition", "attached_pic") != 1 }
            ?: throw FfmpegException("No video track found.")
        val format = root["format"] as? JsonObject
        val durationS = format?.dbl("duration") ?: video.dbl("duration") ?: 0.0
        return VideoInfo(
            durationMs = (durationS * 1000).toLong(),
            width = video.int("width") ?: 0,
            height = video.int("height") ?: 0,
            rotationDegrees = rotation(video),
            frameRate = rate(video.str("avg_frame_rate")).takeIf { it > 0f } ?: rate(video.str("r_frame_rate")),
            bitrate = format?.str("bit_rate")?.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(),
            videoMime = mime(video.str("codec_name")),
            hasAudio = streams.any { it.str("codec_type") == "audio" },
            sizeBytes = sizeBytes ?: format?.str("size")?.toLongOrNull(),
            displayName = displayName,
        )
    }

    /** "60000/1001" → 59.94. */
    fun rate(s: String?): Float {
        if (s.isNullOrBlank()) return 0f
        val parts = s.split('/')
        val n = parts[0].toDoubleOrNull() ?: return 0f
        val d = parts.getOrNull(1)?.toDoubleOrNull() ?: 1.0
        if (d == 0.0) return 0f
        return ((n / d) * 1000).roundToInt() / 1000f
    }

    /** Display-matrix rotation (side data, newer ffprobe) or the legacy `rotate` tag, normalized to 0/90/180/270. */
    fun rotation(video: JsonObject): Int {
        val side = (video["side_data_list"] as? JsonArray)?.map { it.jsonObject }
            ?.firstNotNullOfOrNull { it.dbl("rotation") }
        val tag = (video["tags"] as? JsonObject)?.str("rotate")?.toDoubleOrNull()
        // Display-matrix rotation is counter-clockwise; the tag (and VideoInfo) use clockwise.
        val deg = side?.let { -it } ?: tag ?: 0.0
        return (((deg.roundToInt() % 360) + 360) % 360)
    }

    fun mime(codec: String?): String? = when (codec) {
        null -> null
        "h264" -> "video/avc"
        "hevc" -> "video/hevc"
        "av1" -> "video/av01"
        "vp9" -> "video/x-vnd.on2.vp9"
        "vp8" -> "video/x-vnd.on2.vp8"
        "mpeg4" -> "video/mp4v-es"
        else -> "video/$codec"
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.dbl(k: String) = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
    private fun JsonObject.int(obj: String, k: String) = (this[obj] as? JsonObject)?.int(k)
}

/** Probing plus single-frame and thumbnail grabs. */
class MediaTools(val ff: Ffmpeg) {
    suspend fun probe(file: File): VideoInfo {
        if (!file.isFile) throw FfmpegException("File not found: ${file.name}")
        val out = ff.run(ff.ffprobe, ProbeParser.args(file), 60)
        return ProbeParser.parse(String(out), file.name, file.length())
    }

    /** JPEG of the frame at [ms], scaled to [width] px wide. */
    suspend fun thumbnail(file: File, ms: Long, width: Int, out: File): File {
        out.parentFile?.mkdirs()
        ff.run(
            ff.ffmpeg,
            listOf("-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-ss", Ffmpeg.seconds(ms), "-i", file.path,
                "-frames:v", "1", "-vf", "scale=$width:-2", "-q:v", "4", out.path),
            60,
        )
        if (!out.isFile) throw FfmpegException("No frame at ${ms / 1000}s")
        return out
    }

    /** Raw BGRA pixels of the frame at [ms], scaled to exactly [w]×[h]. */
    suspend fun frameBgra(file: File, ms: Long, w: Int, h: Int): ByteArray {
        val bytes = ff.run(
            ff.ffmpeg,
            listOf("-hide_banner", "-loglevel", "error", "-nostdin", "-ss", Ffmpeg.seconds(ms), "-i", file.path,
                "-frames:v", "1", "-an", "-sn", "-vf", "scale=$w:$h", "-f", "rawvideo", "-pix_fmt", "bgra", "-"),
            30,
        )
        if (bytes.size < w * h * 4) throw FfmpegException("No frame at ${ms / 1000}s")
        return bytes
    }
}
