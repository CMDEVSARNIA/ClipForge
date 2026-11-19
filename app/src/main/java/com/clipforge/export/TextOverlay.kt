package com.clipforge.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import com.clipforge.captions.CaptionMath
import com.clipforge.captions.CaptionPhrase
import com.clipforge.captions.CaptionWord
import com.clipforge.data.db.ClipEntity

enum class TitleStyle(val label: String) {
    PUNCH("Punch"),   // white, heavy black outline, all caps
    BANNER("Banner"), // white on an orange block
    POP("Pop"),       // yellow, thick outline, slight tilt
    ;

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: PUNCH
    }
}

/** Everything drawn on top of the video for one clip. Boxes are in 1080×1920 space. */
data class TextConfig(
    val title: String?,
    val titleStyle: TitleStyle,
    val phrases: List<CaptionPhrase>,
    val titleBox: Box,
    val captionBox: Box,
) {
    val isEmpty: Boolean get() = title.isNullOrBlank() && phrases.isEmpty()

    companion object {
        fun forClip(clip: ClipEntity, startMs: Long, endMs: Long, cam: CamSpec?, title: String = clip.title,
                    showTitle: Boolean = clip.showTitle, style: TitleStyle = TitleStyle.of(clip.titleStyle),
                    showCaptions: Boolean = clip.showCaptions, words: List<CaptionWord> = CaptionMath.decode(clip.captionsJson)): TextConfig {
            val pip = SafeZones.pipRect(cam)
            val inClip = if (showCaptions) words.filter { it.endMs > startMs && it.startMs < endMs } else emptyList()
            return TextConfig(
                title = title.takeIf { showTitle && it.isNotBlank() },
                titleStyle = style,
                phrases = CaptionMath.chunk(inClip),
                titleBox = SafeZones.titleBox(cam, pip),
                captionBox = SafeZones.captionBox(cam, pip),
            )
        }
    }
}

/** Draws [TextConfig] into one reusable frame-sized bitmap, only when what's visible changes. */
class TextRenderer(private val outW: Int, private val outH: Int) {
    private var bitmap: Bitmap? = null
    private var lastKey: Any? = null
    private val heavy: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
    private var titleCache: Triple<String, TitleStyle, Box>? = null
    private var titleLayout: StaticLayout? = null

    fun render(cfg: TextConfig?, tMs: Long): Bitmap {
        val bmp = bitmap ?: Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888).also { bitmap = it }
        val active = cfg?.let { CaptionMath.activeAt(it.phrases, tMs) }
        val key = Pair(System.identityHashCode(cfg), active)
        if (key == lastKey) return bmp
        lastKey = key
        bmp.eraseColor(Color.TRANSPARENT) // also bumps the generation id → overlay re-uploads
        if (cfg == null) return bmp
        val c = Canvas(bmp)
        c.scale(outW / SafeZones.W, outH / SafeZones.H)
        cfg.title?.let { drawTitle(c, it, cfg.titleStyle, cfg.titleBox) }
        active?.let { (pi, wi) -> drawCaption(c, cfg.phrases[pi], wi, cfg.captionBox) }
        return bmp
    }

    private fun titleLayoutFor(text: String, style: TitleStyle, box: Box): StaticLayout {
        val k = Triple(text, style, box)
        if (k == titleCache) titleLayout?.let { return it }
        val t = if (style == TitleStyle.BANNER) text else text.uppercase()
        val pad = if (style == TitleStyle.BANNER) 56 else 24
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = heavy }
        var size = 100f
        var layout: StaticLayout
        while (true) {
            paint.textSize = size
            layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, (box.w - pad).toInt())
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setLineSpacing(0f, 0.92f)
                .setIncludePad(false)
                .build()
            if ((layout.lineCount <= 3 && layout.height <= box.h - pad) || size <= 44f) break
            size -= 6f
        }
        titleCache = k
        titleLayout = layout
        return layout
    }

    private fun drawTitle(c: Canvas, text: String, style: TitleStyle, box: Box) {
        val layout = titleLayoutFor(text, style, box)
        val paint = layout.paint
        val size = paint.textSize
        c.save()
        c.translate(box.cx - layout.width / 2f, box.t + (box.h - layout.height) / 2f)
        when (style) {
            TitleStyle.PUNCH -> {
                outline(paint, Color.BLACK, size * 0.16f); layout.draw(c)
                fill(paint, Color.WHITE); paint.setShadowLayer(14f, 0f, 6f, 0x88000000.toInt()); layout.draw(c)
                paint.clearShadowLayer()
            }
            TitleStyle.BANNER -> {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6A2B.toInt() }
                for (i in 0 until layout.lineCount) {
                    val r = RectF(layout.getLineLeft(i) - 22f, layout.getLineTop(i) - 6f, layout.getLineRight(i) + 22f, layout.getLineBottom(i) + 10f)
                    c.drawRoundRect(r, 18f, 18f, bg)
                }
                fill(paint, Color.WHITE); layout.draw(c)
            }
            TitleStyle.POP -> {
                c.rotate(-3f, layout.width / 2f, layout.height / 2f)
                outline(paint, Color.BLACK, size * 0.2f); layout.draw(c)
                fill(paint, 0xFFFFE600.toInt()); layout.draw(c)
            }
        }
        c.restore()
    }

    private fun drawCaption(c: Canvas, phrase: CaptionPhrase, active: Int, box: Box) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = heavy }
        val words = phrase.words.map { it.text }
        var size = 88f
        // Each slot reserves room for the 1.1× highlighted size, so the line never shifts as the highlight moves.
        fun widths() = words.map { paint.measureText(it) * ACTIVE_SCALE }
        paint.textSize = size
        var space = paint.measureText(" ")
        var ws = widths()
        while (ws.sum() + space * (words.size - 1) > box.w && size > 48f) {
            size -= 4f; paint.textSize = size; space = paint.measureText(" "); ws = widths()
        }
        // Wrap to two lines if it still doesn't fit.
        val lines: List<List<Int>> = if (ws.sum() + space * (words.size - 1) > box.w && words.size > 1) {
            val half = (words.size + 1) / 2
            listOf((0 until half).toList(), (half until words.size).toList())
        } else listOf(words.indices.toList())
        val lineH = size * 1.15f
        var baseline = box.t + (box.h - lineH * lines.size) / 2f + size
        for (line in lines) {
            val lw = line.sumOf { ws[it].toDouble() }.toFloat() + space * (line.size - 1)
            var x = box.cx - lw / 2f
            for (i in line) {
                val isActive = i == active
                c.save()
                val textW = ws[i] / ACTIVE_SCALE
                val tx = x + (ws[i] - textW) / 2f
                if (isActive) c.scale(ACTIVE_SCALE, ACTIVE_SCALE, x + ws[i] / 2f, baseline - size / 3f)
                outline(paint, Color.BLACK, size * 0.18f); c.drawText(words[i], tx, baseline, paint)
                fill(paint, if (isActive) 0xFFFFE600.toInt() else Color.WHITE); c.drawText(words[i], tx, baseline, paint)
                c.restore()
                x += ws[i] + space
            }
            baseline += lineH
        }
    }

    private companion object {
        const val ACTIVE_SCALE = 1.1f
    }

    private fun outline(p: Paint, color: Int, width: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = width; p.strokeJoin = Paint.Join.ROUND; p.color = color
    }

    private fun fill(p: Paint, color: Int) {
        p.style = Paint.Style.FILL; p.color = color
    }
}

/** Media3 overlay that asks [config] + [timeMs] what to draw for each frame. */
@UnstableApi
class TextBitmapOverlay(
    outW: Int,
    outH: Int,
    private val config: () -> TextConfig?,
    private val timeMs: (presentationTimeUs: Long) -> Long,
) : BitmapOverlay() {
    private val renderer = TextRenderer(outW, outH)
    override fun getBitmap(presentationTimeUs: Long): Bitmap = renderer.render(config(), timeMs(presentationTimeUs))
}

/**
 * Maps a media item's frame timestamps to source time. The first frame of a clipped item is
 * [segmentStartMs]; this doesn't depend on whether the pipeline rebases timestamps to zero.
 */
class SegmentClock(private val segmentStartMs: Long) {
    private var firstUs = Long.MIN_VALUE
    fun toSourceMs(ptsUs: Long): Long {
        if (firstUs == Long.MIN_VALUE) firstUs = ptsUs
        return segmentStartMs + (ptsUs - firstUs) / 1000
    }
}
