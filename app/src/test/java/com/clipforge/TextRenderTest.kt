package com.clipforge

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import com.clipforge.captions.CaptionWord
import com.clipforge.data.db.ClipEntity
import com.clipforge.export.CamLayout
import com.clipforge.export.CamSpec
import com.clipforge.export.NRect
import com.clipforge.export.PipCorner
import com.clipforge.export.SafeZones
import com.clipforge.export.TextConfig
import com.clipforge.export.TextRenderer
import com.clipforge.export.TitleStyle
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the real overlay code with Robolectric's native graphics and saves PNGs to build/text-render/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TextRenderTest {
    private val clip = ClipEntity(
        projectId = 1, analysisId = 1, rank = 1, tier = "S", score = 92, startMs = 10_000, endMs = 25_000,
        suggestedStartMs = 10_000, suggestedEndMs = 25_000, title = "1v4 clutch with a pistol?!", hashtags = "", why = "",
        firstPassNote = "", hook = null, payoff = null, clarity = null, emotion = null, trendFit = null,
        loudnessAboveMedianDb = 0f, thumbMs = 0,
    )
    private val words = listOf(
        CaptionWord("No", 12_000, 12_200), CaptionWord("way", 12_200, 12_500), CaptionWord("he", 12_500, 12_650),
        CaptionWord("actually", 12_650, 13_100), CaptionWord("hit", 13_100, 13_300), CaptionWord("that!", 13_300, 13_700),
    )

    private fun background(): Bitmap {
        val b = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 1920f, 0xFF3A7BD5.toInt(), 0xFF1B3D1C.toInt(), Shader.TileMode.CLAMP) })
        // Platform UI zones, drawn translucent so the image shows text stays clear of them.
        val ui = Paint().apply { color = 0x55FF0000 }
        c.drawRect(0f, 0f, 1080f, SafeZones.TOP_UI, ui)
        c.drawRect(0f, SafeZones.BOTTOM_UI, 1080f, 1920f, ui)
        c.drawRect(SafeZones.RIGHT_RAIL_X, SafeZones.RAIL_TOP, 1080f, SafeZones.RAIL_BOTTOM, ui)
        return b
    }

    private fun render(name: String, style: TitleStyle, cam: CamSpec?, tMs: Long, wm: com.clipforge.export.Watermark? = null) {
        val cfg = TextConfig.forClip(clip, clip.startMs, clip.endMs, cam, style = style, words = words, watermark = wm)
        if (wm != null) {
            val reserve = com.clipforge.export.WatermarkPlacement.topReserve(wm.size)
            assertTrue("title below watermark", cfg.titleBox.t >= reserve)
        }
        val overlay = TextRenderer(1080, 1920).render(cfg, tMs)
        var painted = 0
        for (y in 0 until 1920 step 8) for (x in 0 until 1080 step 8) if (Color.alpha(overlay.getPixel(x, y)) > 0) painted++
        assertTrue("$name drew nothing", painted > 100)
        val out = background()
        Canvas(out).drawBitmap(overlay, 0f, 0f, null)
        val dir = File("build/text-render").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun renderStyles() {
        val cam = NRect(0.75f, 0.65f, 0.98f, 0.97f)
        render("punch", TitleStyle.PUNCH, null, 12_300)
        render("banner", TitleStyle.BANNER, null, 12_800)
        render("pop", TitleStyle.POP, null, 13_200)
        render("split_punch", TitleStyle.PUNCH, CamSpec(cam, CamLayout.SPLIT), 12_300)
        render("pip_tr_pop", TitleStyle.POP, CamSpec(cam, CamLayout.PIP, PipCorner.TR), 12_800)
        render("pip_br_banner", TitleStyle.BANNER, CamSpec(cam, CamLayout.PIP, PipCorner.BR), 13_200)
    }

    @Test fun renderCaptionStyles() {
        com.clipforge.export.CaptionStyle.entries.forEach { style ->
            val cfg = TextConfig.forClip(clip, clip.startMs, clip.endMs, null, showTitle = false, words = words, captionStyle = style)
            val overlay = TextRenderer(1080, 1920).render(cfg, 12_800) // "actually" is the spoken word
            val out = background()
            Canvas(out).drawBitmap(overlay, 0f, 0f, null)
            File("build/text-render").mkdirs()
            File("build/text-render/caption_${style.name.lowercase()}.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun renderWatermarks() {
        val logo = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            c.drawCircle(32f, 32f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFE624C.toInt() })
        }
        fun wm(pos: com.clipforge.export.WatermarkPosition, size: com.clipforge.export.WatermarkSize = com.clipforge.export.WatermarkSize.M, withLogo: Boolean = true) =
            com.clipforge.export.Watermark("@clipforge.gg", if (withLogo) logo else null, pos, 0.8f, size)
        val cam = NRect(0.75f, 0.65f, 0.98f, 0.97f)
        render("wm_tl", TitleStyle.PUNCH, null, 12_300, wm(com.clipforge.export.WatermarkPosition.TL))
        render("wm_moving_t4", TitleStyle.PUNCH, null, 4_100, wm(com.clipforge.export.WatermarkPosition.MOVING)) // TR slot
        render("wm_moving_t8", TitleStyle.BANNER, null, 8_500, wm(com.clipforge.export.WatermarkPosition.MOVING, com.clipforge.export.WatermarkSize.L)) // BL slot (8-12 s)
        render("wm_pip_tr", TitleStyle.POP, CamSpec(cam, CamLayout.PIP, PipCorner.TR), 12_800, wm(com.clipforge.export.WatermarkPosition.TR, withLogo = false))
    }
}
