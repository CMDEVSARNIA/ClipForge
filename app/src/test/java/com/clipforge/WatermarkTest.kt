package com.clipforge

import com.clipforge.export.Box
import com.clipforge.export.CamLayout
import com.clipforge.export.CamSpec
import com.clipforge.export.NRect
import com.clipforge.export.PipCorner
import com.clipforge.export.SafeZones
import com.clipforge.export.WatermarkPlacement
import com.clipforge.export.WatermarkPosition
import com.clipforge.export.WatermarkSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatermarkTest {
    private val cam = NRect(0.75f, 0.65f, 0.98f, 0.97f)

    @Test fun cornersStayInsideThePlatformSafeArea() {
        for (corner in listOf(WatermarkPosition.TL, WatermarkPosition.TR, WatermarkPosition.BL)) {
            for (size in WatermarkSize.entries) {
                val b = WatermarkPlacement.boxFor(corner, 360f, size.heightPx)
                assertTrue("$corner top", b.t >= SafeZones.TOP_UI)
                assertTrue("$corner bottom", b.b <= SafeZones.BOTTOM_UI)
                assertTrue("$corner sides", b.l >= SafeZones.SIDE - 0.01f && b.r <= SafeZones.W - SafeZones.SIDE + 0.01f)
                assertFalse("$corner rail", b.intersects(SafeZones.rightRail))
            }
        }
    }

    @Test fun movingModeHopsEveryFewSeconds() {
        val seen = (0 until 6).map { WatermarkPlacement.cornerAt(WatermarkPosition.MOVING, null, null, it * WatermarkPlacement.HOP_MS) }
        assertEquals(listOf(WatermarkPosition.TL, WatermarkPosition.TR, WatermarkPosition.BL, WatermarkPosition.TL, WatermarkPosition.TR, WatermarkPosition.BL), seen)
        assertEquals(WatermarkPosition.TL, WatermarkPlacement.cornerAt(WatermarkPosition.MOVING, null, null, WatermarkPlacement.HOP_MS - 1))
    }

    @Test fun pipCornerIsAvoided() {
        val trPip = CamSpec(cam, CamLayout.PIP, PipCorner.TR)
        val pipRect = SafeZones.pipRect(trPip)!!
        val free = WatermarkPlacement.freeCorners(trPip, pipRect)
        assertFalse(WatermarkPosition.TR in free)
        // A fixed TR watermark moves to a free corner instead of hiding under the cam.
        assertTrue(WatermarkPlacement.cornerAt(WatermarkPosition.TR, trPip, pipRect, 0) != WatermarkPosition.TR)
        val blPip = CamSpec(cam, CamLayout.PIP, PipCorner.BL)
        assertFalse(WatermarkPosition.BL in WatermarkPlacement.freeCorners(blPip, SafeZones.pipRect(blPip)))
    }

    @Test fun bottomLeftClearsCaptions() {
        val bl = WatermarkPlacement.boxFor(WatermarkPosition.BL, 400f, WatermarkSize.L.heightPx)
        assertFalse(bl.intersects(SafeZones.captionBox(null, null)))
    }

    @Test fun titleStartsBelowATopWatermark() {
        val wmBottom = WatermarkPlacement.boxFor(WatermarkPosition.TL, 300f, WatermarkSize.L.heightPx).b
        assertTrue(WatermarkPlacement.topReserve(WatermarkSize.L) > wmBottom)
        assertTrue(SafeZones.titleBox(null, null).t < WatermarkPlacement.topReserve(WatermarkSize.L)) // so forClip must push it
    }

    @Suppress("unused") private fun Box.area() = w * h
}
