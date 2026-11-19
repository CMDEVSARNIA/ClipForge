package com.clipforge

import com.clipforge.media.VideoFormat
import com.clipforge.media.VideoInfo
import com.clipforge.ui.components.ScrubMath
import com.clipforge.util.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeFormatTest {
    @Test fun minutesAndSeconds() = assertEquals("2:05", TimeFormat.format(125_000))
    @Test fun hours() = assertEquals("1:00:07", TimeFormat.format(3_607_000))
    @Test fun tenths() = assertEquals("0:12.3", TimeFormat.format(12_345, tenths = true))
    @Test fun negativeClampsToZero() = assertEquals("0:00", TimeFormat.format(-50))
}

class VideoFormatTest {
    private fun info(w: Int, h: Int, rot: Int = 0) =
        VideoInfo(1000, w, h, rot, 30f, null, null, true, null, null)

    @Test fun rotatedVideoSwapsDisplayDims() {
        val i = info(1920, 1080, rot = 90)
        assertEquals(1080, i.displayWidth)
        assertEquals(1920, i.displayHeight)
        assertEquals("1080×1920 · 1080p", i.resolutionLabel)
    }

    @Test fun negativeRotationHandled() = assertEquals(1080, info(1920, 1080, rot = -90).displayWidth)

    @Test fun resolutionClasses() {
        assertEquals("1280×720 · 720p", VideoFormat.resolutionLabel(1280, 720))
        assertEquals("3840×2160 · 4K", VideoFormat.resolutionLabel(3840, 2160))
        assertEquals("Unknown", VideoFormat.resolutionLabel(0, 0))
    }

    @Test fun frameRateSnapping() {
        assertEquals("60 fps", VideoFormat.frameRateLabel(60f))
        assertEquals("59.94 fps", VideoFormat.frameRateLabel(59.93f))
        assertEquals("30 fps", VideoFormat.frameRateLabel(30.02f))
        assertEquals("29.97 fps", VideoFormat.frameRateLabel(29.97f))
        assertEquals("Unknown", VideoFormat.frameRateLabel(0f))
    }

    @Test fun bytes() {
        assertEquals("512 B", VideoFormat.bytesLabel(512))
        assertEquals("1.5 GB", VideoFormat.bytesLabel(1_610_612_736))
    }
}

class ScrubMathTest {
    @Test fun positionClampsToBounds() {
        assertEquals(0L, ScrubMath.positionForX(-10f, 100f, 60_000))
        assertEquals(60_000L, ScrubMath.positionForX(500f, 100f, 60_000))
        assertEquals(30_000L, ScrubMath.positionForX(50f, 100f, 60_000))
    }

    @Test fun fineFactorSlowsWithDistance() {
        assertEquals(1f, ScrubMath.fineFactor(0f, 2f))
        assertEquals(0.25f, ScrubMath.fineFactor(160f, 2f)) // 80dp
        assertEquals(0.05f, ScrubMath.fineFactor(300f, 2f)) // 150dp
        assertEquals(0.01f, ScrubMath.fineFactor(-1000f, 2f))
    }

    @Test fun dragAppliesFactorAndClamps() {
        // 1 hour over 1000px: 100px = 6 min at full speed, 1.5 min at quarter speed.
        val hour = 3_600_000L
        assertEquals(360_000.0, ScrubMath.applyDrag(0.0, 100f, 1000f, hour, 1f), 0.5)
        assertEquals(90_000.0, ScrubMath.applyDrag(0.0, 100f, 1000f, hour, 0.25f), 0.5)
        assertEquals(0.0, ScrubMath.applyDrag(1000.0, -500f, 1000f, hour, 1f), 0.0)
    }

    @Test fun distanceOutside() {
        assertEquals(0f, ScrubMath.distanceOutside(20f, 60f))
        assertEquals(15f, ScrubMath.distanceOutside(-15f, 60f))
        assertEquals(40f, ScrubMath.distanceOutside(100f, 60f))
        assertNull(ScrubMath.factorLabel(1f))
    }
}
