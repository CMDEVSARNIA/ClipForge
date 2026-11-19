package com.clipforge.assistant

import com.clipforge.analysis.MomentParser
import com.clipforge.captions.CaptionMath
import com.clipforge.data.db.ClipEntity
import com.clipforge.export.CamLayout
import com.clipforge.export.CropMath
import com.clipforge.export.CropMode
import com.clipforge.export.PipCorner
import com.clipforge.export.TitleStyle
import com.clipforge.util.TimeFormat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale
import kotlin.math.roundToLong

/** What the assistant's tools can see and change. Pure, so it's unit-tested without Android. */
data class ProjectState(
    val clips: List<ClipEntity>,
    val durationMs: Long,
    val hasFacecam: Boolean,
)

/** Result of one tool call: the clips it changed (to save) and the text sent back to Claude / shown in the chat. */
data class ToolOutcome(val changed: List<ClipEntity>, val result: String, val isError: Boolean = false)

object ChatTools {
    const val MIN_LEN_MS = 2_000L

    private fun strictTool(name: String, description: String, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit, required: List<String>) =
        buildJsonObject {
            put("name", name)
            put("description", description)
            put("strict", true)
            putJsonObject("input_schema") {
                put("type", "object")
                putJsonObject("properties", props)
                putJsonArray("required") { required.forEach { add(it) } }
                put("additionalProperties", false)
            }
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.clipProp() =
        putJsonObject("clip") { put("type", "integer"); put("description", "The clip's rank number as the user sees it (1 = best).") }

    private fun kotlinx.serialization.json.JsonObjectBuilder.enumProp(name: String, values: List<String>, desc: String) =
        putJsonObject(name) { put("type", "string"); putJsonArray("enum") { values.forEach { add(it) } }; put("description", desc) }

    /** Tool definitions (deterministic order so the prompt cache prefix stays stable). */
    val DEFINITIONS: JsonArray = buildJsonArray {
        add(strictTool(
            "get_clip_details",
            "Read one clip's details: transcript, why it was picked, subscores, crop and layout. Call this before rewriting a title or judging a clip you haven't looked at in this conversation.",
            { clipProp() }, listOf("clip"),
        ))
        add(strictTool(
            "trim_clip",
            "Move a clip's edges by a number of seconds. Positive start_delta_s starts later (cuts the beginning); negative end_delta_s ends earlier (cuts the ending). Use it for 'shorter', 'cut the intro', 'end on the kill', 'add a second before'.",
            {
                clipProp()
                putJsonObject("start_delta_s") { put("type", "number"); put("description", "Seconds to move the start; 0 to keep it.") }
                putJsonObject("end_delta_s") { put("type", "number"); put("description", "Seconds to move the end; 0 to keep it.") }
            },
            listOf("clip", "start_delta_s", "end_delta_s"),
        ))
        add(strictTool(
            "set_clip_range",
            "Set a clip's exact start and end, in seconds from the start of the source video. Use it when the user gives timestamps.",
            {
                clipProp()
                putJsonObject("start_s") { put("type", "number") }
                putJsonObject("end_s") { put("type", "number") }
            },
            listOf("clip", "start_s", "end_s"),
        ))
        add(strictTool(
            "set_crop",
            "Change how a clip is framed in 9:16. AUTO: fit+blur for text cards / wide shots, else center. CENTER: fill with the middle. FIT_BLUR: whole frame over a blurred copy. ZOOM_FIT: a centered zoom box over blur. PAN: fill, shifted by pan_x (-1 left edge … 1 right edge). This replaces any per-segment framing.",
            {
                clipProp()
                enumProp("mode", CropMode.entries.map { it.name }, "Framing mode.")
                putJsonObject("pan_x") { put("type", "number"); put("description", "Only for PAN, -1..1; send 0 otherwise.") }
            },
            listOf("clip", "mode", "pan_x"),
        ))
        add(strictTool(
            "set_layout",
            "Facecam layout. NONE: gameplay only. SPLIT: cam on top, gameplay below. PIP: cam in a rounded corner box. Needs a facecam set up on the project. clip 0 means every clip.",
            {
                clipProp()
                enumProp("layout", CamLayout.entries.map { it.name }, "Layout.")
                enumProp("pip_corner", PipCorner.entries.map { it.name }, "Corner for PIP (TL, TR, BL, BR); send TR when unused.")
            },
            listOf("clip", "layout", "pip_corner"),
        ))
        add(strictTool(
            "set_title",
            "Rewrite a clip's title (shown on the video and used as the file name). 50 characters or fewer, true to the clip. Optionally change the title style.",
            {
                clipProp()
                putJsonObject("title") { put("type", "string") }
                enumProp("style", listOf("KEEP") + TitleStyle.entries.map { it.name }, "PUNCH, BANNER, POP, or KEEP to leave it.")
            },
            listOf("clip", "title", "style"),
        ))
        add(strictTool(
            "set_hashtags",
            "Replace a clip's hashtags (up to 5, no spaces).",
            {
                clipProp()
                putJsonObject("hashtags") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
            },
            listOf("clip", "hashtags"),
        ))
        add(strictTool(
            "set_overlays",
            "Turn the title overlay and/or speech captions on or off for a clip. clip 0 means every clip.",
            {
                clipProp()
                enumProp("title", listOf("KEEP", "ON", "OFF"), "Title overlay.")
                enumProp("captions", listOf("KEEP", "ON", "OFF"), "Speech captions.")
            },
            listOf("clip", "title", "captions"),
        ))
    }

    private fun s(ms: Long) = String.format(Locale.US, "%.1fs", ms / 1000.0)

    /** One line per clip, sent with each user message so Claude always sees the current state. */
    fun stateSummary(st: ProjectState): String = buildString {
        appendLine("Clips (rank · tier · score · range · length · crop · layout · title):")
        st.clips.sortedBy { it.rank }.forEach { c ->
            appendLine(
                "${c.rank} · ${c.tier} · ${c.score} · ${TimeFormat.format(c.startMs, true)}–${TimeFormat.format(c.endMs, true)} · ${s(c.endMs - c.startMs)} · " +
                    "${if (c.segmentsJson != null) "custom segments" else c.cropMode} · ${c.camLayout} · \"${c.title}\"" +
                    (if (c.exportedAt != null) " · exported" else "")
            )
        }
        append("Facecam set up: ${if (st.hasFacecam) "yes" else "no"}. Source length: ${TimeFormat.format(st.durationMs)}.")
    }

    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    fun execute(name: String, input: JsonObject, st: ProjectState): ToolOutcome {
        fun err(msg: String) = ToolOutcome(emptyList(), msg, isError = true)
        val rank = input.int("clip") ?: return err("Missing clip number.")
        val all = rank == 0
        val clip = st.clips.firstOrNull { it.rank == rank }
        if (!all && clip == null) return err("There is no clip $rank. Clips are numbered 1–${st.clips.size}.")
        return when (name) {
            "get_clip_details" -> {
                val c = clip ?: return err("Pick one clip.")
                val words = CaptionMath.decode(c.captionsJson).filter { it.endMs > c.startMs && it.startMs < c.endMs }
                ToolOutcome(emptyList(), buildString {
                    appendLine("Clip ${c.rank}: \"${c.title}\" (${c.tier}, ${c.score}/100), ${TimeFormat.format(c.startMs, true)}–${TimeFormat.format(c.endMs, true)} (${s(c.endMs - c.startMs)}).")
                    appendLine("Why picked: ${c.why}")
                    appendLine("Subscores: hook ${c.hook ?: "-"}, payoff ${c.payoff ?: "-"}, clarity ${c.clarity ?: "-"}, emotion ${c.emotion ?: "-"}, trend fit ${c.trendFit ?: "-"}.")
                    appendLine("Crop: ${c.cropMode}${if (c.segmentsJson != null) " (custom segments)" else ""}. Layout: ${c.camLayout}. Title style: ${c.titleStyle}. Hashtags: ${c.hashtags.ifBlank { "none" }}.")
                    append("Transcript: ").append(
                        when {
                            words.isNotEmpty() -> words.joinToString(" ") { it.text }
                            c.captionStatus == "NO_SPEECH" -> "(no speech)"
                            else -> "(not transcribed yet)"
                        }
                    )
                })
            }
            "trim_clip", "set_clip_range" -> {
                val c = clip ?: return err("Pick one clip.")
                val (ns, ne) = if (name == "trim_clip") {
                    val ds = ((input.num("start_delta_s") ?: 0.0) * 1000).roundToLong()
                    val de = ((input.num("end_delta_s") ?: 0.0) * 1000).roundToLong()
                    (c.startMs + ds) to (c.endMs + de)
                } else {
                    ((input.num("start_s") ?: return err("Missing start_s.")) * 1000).roundToLong() to
                        ((input.num("end_s") ?: return err("Missing end_s.")) * 1000).roundToLong()
                }
                val start = ns.coerceIn(0, st.durationMs)
                val end = ne.coerceIn(0, st.durationMs)
                if (end - start < MIN_LEN_MS) return err("That would leave ${s((end - start).coerceAtLeast(0))}; clips need at least ${s(MIN_LEN_MS)}.")
                val updated = c.copy(startMs = start, endMs = end, edited = true)
                ToolOutcome(listOf(updated), "Clip ${c.rank}: ${s(c.endMs - c.startMs)} → ${s(end - start)} (${TimeFormat.format(start, true)}–${TimeFormat.format(end, true)}).")
            }
            "set_crop" -> {
                val c = clip ?: return err("Pick one clip.")
                val mode = CropMode.entries.firstOrNull { it.name == input.str("mode") } ?: return err("Unknown mode.")
                val pan = (input.num("pan_x") ?: 0.0).toFloat().coerceIn(-1f, 1f)
                val updated = c.copy(
                    cropMode = mode.name, panX = if (mode == CropMode.PAN) pan else c.panX, segmentsJson = null,
                    zoomRect = if (mode == CropMode.ZOOM_FIT) (c.zoomRect ?: CropMath.defaultZoomRect().toCsv()) else c.zoomRect,
                )
                ToolOutcome(listOf(updated), "Clip ${c.rank} framing: ${mode.label}" + if (mode == CropMode.PAN) String.format(Locale.US, " (pan %.1f)", pan) else ".")
            }
            "set_layout" -> {
                val layout = CamLayout.entries.firstOrNull { it.name == input.str("layout") } ?: return err("Unknown layout.")
                if (layout != CamLayout.NONE && !st.hasFacecam) return err("No facecam is set up for this project yet (project screen → Facecam → Set up).")
                val corner = PipCorner.entries.firstOrNull { it.name == input.str("pip_corner") } ?: PipCorner.TR
                val targets = if (all) st.clips else listOf(clip!!)
                ToolOutcome(
                    targets.map { it.copy(camLayout = layout.name, pipCorner = corner.name) },
                    "${if (all) "All clips" else "Clip $rank"}: ${layout.label}" + if (layout == CamLayout.PIP) " (${corner.label.lowercase()})." else ".",
                )
            }
            "set_title" -> {
                val c = clip ?: return err("Pick one clip.")
                val t = input.str("title")?.trim()?.take(70).orEmpty()
                if (t.isBlank()) return err("Title is empty.")
                val style = input.str("style")?.takeIf { it != "KEEP" }?.let { TitleStyle.of(it).name } ?: c.titleStyle
                ToolOutcome(listOf(c.copy(title = t, titleStyle = style)), "Clip ${c.rank} title: \"$t\"" + if (style != c.titleStyle) " (${TitleStyle.of(style).label} style)." else ".")
            }
            "set_hashtags" -> {
                val c = clip ?: return err("Pick one clip.")
                val tags = MomentParser.normalizeHashtags(input["hashtags"]).take(5)
                if (tags.isEmpty()) return err("No valid hashtags.")
                ToolOutcome(listOf(c.copy(hashtags = tags.joinToString(" "))), "Clip ${c.rank} hashtags: ${tags.joinToString(" ")}.")
            }
            "set_overlays" -> {
                val t = input.str("title")
                val cap = input.str("captions")
                val targets = if (all) st.clips else listOf(clip!!)
                val changed = targets.map { c ->
                    c.copy(
                        showTitle = when (t) { "ON" -> true; "OFF" -> false; else -> c.showTitle },
                        showCaptions = when (cap) { "ON" -> true; "OFF" -> false; else -> c.showCaptions },
                    )
                }
                val note = if (cap == "ON" && targets.any { it.captionsJson == null }) " Some clips have no captions yet; generate them from the clip's Text tab." else ""
                ToolOutcome(changed, "${if (all) "All clips" else "Clip $rank"}: title ${t?.lowercase()}, captions ${cap?.lowercase()}.$note")
            }
            else -> err("Unknown tool $name.")
        }
    }
}
