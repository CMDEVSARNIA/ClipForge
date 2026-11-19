package com.clipforge

import com.clipforge.export.CamLayout
import com.clipforge.export.CamSpec
import com.clipforge.export.CropMath
import com.clipforge.export.CropMode
import com.clipforge.export.LayoutRequest
import com.clipforge.export.LayoutSpec
import com.clipforge.export.NRect
import com.clipforge.export.PipCorner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CamLayoutTest {
    private val eps = 1e-4f
    private val cam = NRect(0.75f, 0.65f, 0.98f, 0.97f) // bottom-right webcam, 441.6×345.6 px on 1080p

    private fun pxAspect(r: NRect, w: Int, h: Int) = r.w * w / (r.h * h)

    @Test fun splitPutsCamOnTopThirtyFivePercent() {
        val s = CropMath.spec(LayoutRequest(CropMode.CENTER, cam = CamSpec(cam, CamLayout.SPLIT)), 1920, 1080)
        assertNull(s.background)
        val (game, camL) = s.layers
        assertEquals(NRect(0f, 0f, 1f, 0.35f), camL.dst)
        assertEquals(NRect(0f, 0.35f, 1f, 1f), game.dst)
        // No distortion: source aspect (px) equals destination aspect (px) for both bands.
        assertEquals(pxAspect(camL.dst, 1080, 1920), pxAspect(camL.src, 1920, 1080), 1e-3f)
        assertEquals(pxAspect(game.dst, 1080, 1920), pxAspect(game.src, 1920, 1080), 1e-3f)
        // Cam source stays inside the drawn cam box.
        assertTrue(camL.src.l >= cam.l - eps && camL.src.r <= cam.r + eps && camL.src.t >= cam.t - eps && camL.src.b <= cam.b + eps)
    }

    @Test fun splitGameplayHonorsPanAndZoom() {
        val panned = CropMath.spec(LayoutRequest(CropMode.PAN, panX = 1f, cam = CamSpec(cam, CamLayout.SPLIT)), 1920, 1080).layers[0]
        assertEquals(1f, panned.src.r, eps)
        val z = NRect(0.3f, 0.2f, 0.7f, 0.8f)
        val zoomed = CropMath.spec(LayoutRequest(CropMode.ZOOM_FIT, zoomRect = z, cam = CamSpec(cam, CamLayout.SPLIT)), 1920, 1080).layers[0]
        assertTrue(zoomed.src.l >= z.l - eps && zoomed.src.r <= z.r + eps && zoomed.src.t >= z.t - eps && zoomed.src.b <= z.b + eps)
    }

    @Test fun pipKeepsCamAspectAndRoundedCorners() {
        val s = CropMath.spec(LayoutRequest(CropMode.CENTER, cam = CamSpec(cam, CamLayout.PIP, PipCorner.TR)), 1920, 1080)
        assertEquals(2, s.layers.size)
        val pip = s.layers[1]
        assertEquals(CropMath.PIP_RADIUS_PX, pip.cornerRadiusPx, 0f)
        assertEquals(pxAspect(pip.src, 1920, 1080), pxAspect(pip.dst, 1080, 1920), 1e-3f)
        assertEquals(CropMath.PIP_WIDTH, pip.dst.w, eps)
    }

    @Test fun pipCornersRespectSafeMargins() {
        for (c in PipCorner.entries) {
            val d = CropMath.spec(LayoutRequest(CropMode.CENTER, cam = CamSpec(cam, CamLayout.PIP, c)), 1920, 1080).layers[1].dst
            assertTrue("$c left", d.l >= CropMath.PIP_SIDE_MARGIN - eps)
            assertTrue("$c right", d.r <= 1f - CropMath.PIP_SIDE_MARGIN + eps)
            assertTrue("$c top", d.t >= CropMath.PIP_TOP_MARGIN - eps)
            assertTrue("$c bottom", d.b <= 1f - CropMath.PIP_BOTTOM_MARGIN + eps)
        }
        val tl = CropMath.spec(LayoutRequest(CropMode.CENTER, cam = CamSpec(cam, CamLayout.PIP, PipCorner.TL)), 1920, 1080).layers[1].dst
        val br = CropMath.spec(LayoutRequest(CropMode.CENTER, cam = CamSpec(cam, CamLayout.PIP, PipCorner.BR)), 1920, 1080).layers[1].dst
        assertTrue(tl.l < 0.5f && tl.t < 0.5f && br.r > 0.5f && br.b > 0.5f)
    }

    @Test fun pipOverFitBlurKeepsBackground() {
        val s = CropMath.spec(LayoutRequest(CropMode.FIT_BLUR, cam = CamSpec(cam, CamLayout.PIP)), 1920, 1080)
        assertTrue(s.background != null)
        assertEquals(2, s.layers.size)
    }

    @Test fun extremeCamBoxIsCroppedToSaneAspect() {
        val sliver = NRect(0.9f, 0f, 0.95f, 1f) // 96×1080 px: very tall
        val pip = CropMath.spec(LayoutRequest(CropMode.CENTER, cam = CamSpec(sliver, CamLayout.PIP)), 1920, 1080).layers[1]
        assertEquals(CropMath.PIP_MIN_ASPECT, pxAspect(pip.src, 1920, 1080), 1e-3f)
    }

    @Test fun noneLayoutIsPlainSpec() {
        assertEquals(
            CropMath.spec(LayoutRequest(CropMode.CENTER), 1920, 1080),
            CropMath.spec(LayoutRequest(CropMode.CENTER, cam = CamSpec(cam, CamLayout.NONE)), 1920, 1080),
        )
    }

    /** Writes the app's own specs for the WebGL render check (build/layout-specs.json). */
    @Test fun dumpSpecsForRenderCheck() {
        fun r(n: NRect) = "[${n.l},${n.t},${n.r},${n.b}]"
        fun j(s: LayoutSpec) = "{\"bg\":${s.background?.let { r(it) } ?: "null"},\"dim\":${s.bgDim},\"layers\":[" +
            s.layers.joinToString(",") { "{\"src\":${r(it.src)},\"dst\":${r(it.dst)},\"radius\":${it.cornerRadiusPx}}" } + "]}"
        val c = NRect(0.78f, 0.62f, 0.99f, 0.98f)
        val specs = linkedMapOf(
            "split" to CropMath.spec(LayoutRequest(CropMode.CENTER, cam = CamSpec(c, CamLayout.SPLIT)), 1920, 1080),
            "pip_tr" to CropMath.spec(LayoutRequest(CropMode.CENTER, cam = CamSpec(c, CamLayout.PIP, PipCorner.TR)), 1920, 1080),
            "pip_bl_fitblur" to CropMath.spec(LayoutRequest(CropMode.FIT_BLUR, cam = CamSpec(c, CamLayout.PIP, PipCorner.BL)), 1920, 1080),
            "gameplay_only" to CropMath.spec(LayoutRequest(CropMode.CENTER, cam = CamSpec(c, CamLayout.NONE)), 1920, 1080),
        )
        File("build").mkdirs()
        File("build/layout-specs.json").writeText("{" + specs.entries.joinToString(",") { "\"${it.key}\":${j(it.value)}" } + "}")
    }
}
