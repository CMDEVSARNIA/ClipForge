package com.clipforge

import com.clipforge.export.CropMath
import com.clipforge.export.CropMode
import com.clipforge.export.ExportMath
import com.clipforge.export.LayoutRequest
import com.clipforge.export.Mp4FastStart
import com.clipforge.export.NRect
import com.clipforge.export.Segment
import com.clipforge.export.SegmentPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer

class CropMathTest {
    private val eps = 1e-4f
    private fun assertRect(e: NRect, a: NRect) {
        assertEquals("l", e.l, a.l, eps); assertEquals("t", e.t, a.t, eps)
        assertEquals("r", e.r, a.r, eps); assertEquals("b", e.b, a.b, eps)
    }

    @Test fun centerCropOf1080pLandscape() {
        // 9:16 window of a 16:9 frame: 607.5 px of 1920 wide, centered.
        val r = CropMath.coverRect(1920, 1080, 1080, 1920)
        assertEquals(607.5f / 1920f, r.w, eps)
        assertEquals(0.5f, r.cx, eps)
        assertEquals(0f, r.t, eps); assertEquals(1f, r.b, eps)
    }

    @Test fun centerCropOf720pIsSameShape() {
        assertRect(CropMath.coverRect(1920, 1080, 1080, 1920), CropMath.coverRect(1280, 720, 1080, 1920))
    }

    @Test fun panReachesBothEdges() {
        assertEquals(0f, CropMath.coverRect(1920, 1080, 1080, 1920, panX = -1f).l, eps)
        assertEquals(1f, CropMath.coverRect(1920, 1080, 1080, 1920, panX = 1f).r, eps)
        assertEquals(1f, CropMath.coverRect(1920, 1080, 1080, 1920, panX = 5f).r, eps) // clamped
    }

    @Test fun portraitSourceCoversFullFrame() {
        assertRect(NRect.FULL, CropMath.coverRect(1080, 1920, 1080, 1920))
    }

    @Test fun taller9by20SourceCropsVertically() {
        val r = CropMath.coverRect(1080, 2400, 1080, 1920)
        assertEquals(0f, r.l, eps); assertEquals(1f, r.r, eps)
        assertEquals(0.8f, r.h, eps)
        assertEquals(0.5f, r.cy, eps)
    }

    @Test fun fitBlurLetterboxesFullFrameAtFullWidth() {
        val s = CropMath.spec(LayoutRequest(CropMode.FIT_BLUR), 1920, 1080)
        val dst = s.layers.single().dst
        assertEquals(0f, dst.l, eps); assertEquals(1f, dst.r, eps)
        assertEquals(607.5f / 1920f, dst.h, eps) // 1080 * 1080/1920 px tall
        assertEquals(0.5f, dst.cy, eps)
        assertRect(NRect.FULL, s.layers.single().src)
        assertTrue(s.background != null)
    }

    @Test fun zoomFitPreservesAspect() {
        val z = NRect(0.25f, 0.25f, 0.75f, 0.75f) // 960×540 region of 1080p: still 16:9
        val s = CropMath.spec(LayoutRequest(CropMode.ZOOM_FIT, zoomRect = z), 1920, 1080)
        val l = s.layers.single()
        val dstAspect = (l.dst.w * 1080) / (l.dst.h * 1920)
        val srcAspect = (l.src.w * 1920) / (l.src.h * 1080)
        assertEquals(srcAspect, dstAspect, 1e-3f)
        assertEquals(1f, l.dst.w, eps)
    }

    @Test fun tallZoomBoxFitsByHeight() {
        val z = NRect(0.4f, 0f, 0.5f, 1f) // 192×1080: taller than 9:16
        val l = CropMath.spec(LayoutRequest(CropMode.ZOOM_FIT, zoomRect = z), 1920, 1080).layers.single()
        assertEquals(1f, l.dst.h, eps)
        assertTrue(l.dst.w < 1f)
    }

    @Test fun centerAndPanHaveNoBackground() {
        assertNull(CropMath.spec(LayoutRequest(CropMode.CENTER), 1920, 1080).background)
        assertNull(CropMath.spec(LayoutRequest(CropMode.PAN, panX = 0.3f), 1920, 1080).background)
    }

    @Test fun rectDragStaysInBoundsAndAboveMinimum() {
        val r = NRect(0.1f, 0.1f, 0.5f, 0.5f)
        val moved = CropMath.drag(r, CropMath.RectHandle.MOVE, 0.9f, -0.5f)
        assertEquals(1f, moved.r, eps); assertEquals(0f, moved.t, eps); assertEquals(r.w, moved.w, eps)
        val shrunk = CropMath.drag(r, CropMath.RectHandle.BOTTOM_RIGHT, -1f, -1f)
        assertEquals(CropMath.MIN_RECT, shrunk.w, eps)
        assertEquals(CropMath.RectHandle.TOP_LEFT, CropMath.pickHandle(r, 0.11f, 0.12f, 0.05f))
        assertEquals(CropMath.RectHandle.MOVE, CropMath.pickHandle(r, 0.3f, 0.3f, 0.05f))
        assertNull(CropMath.pickHandle(r, 0.9f, 0.9f, 0.05f))
    }

    @Test fun rectCsvRoundTrip() {
        val r = NRect(0.1f, 0.2f, 0.3f, 0.4f)
        assertEquals(r, NRect.fromCsv(r.toCsv()))
        assertNull(NRect.fromCsv("1,2,3"))
        assertNull(NRect.fromCsv("0.5,0.5,0.1,0.1"))
    }
}

class SegmentPlannerTest {
    @Test fun noFlagsMeansOneCenterSegment() {
        val s = SegmentPlanner.auto(10_000, 25_000, emptyList(), emptyList())
        assertEquals(listOf(Segment(10_000, 25_000, CropMode.CENTER)), s)
    }

    @Test fun textCardBecomesFitBlurSnappedToCut() {
        // "WASTED" flagged at 20 s (covers 19–21 s); a cut at 19.4 s pulls the start onto it.
        val s = SegmentPlanner.auto(10_000, 25_000, listOf(19_400), listOf(20_000))
        assertEquals(listOf(CropMode.CENTER, CropMode.FIT_BLUR, CropMode.CENTER), s.map { it.mode })
        assertEquals(21_000, s[1].endMs)
        assertEquals(CropMode.CENTER, s[0].mode)
        assertEquals(19_400, s[0].endMs)
        assertEquals(CropMode.FIT_BLUR, s[1].mode)
        assertEquals(19_400, s[1].startMs)
    }

    @Test fun adjacentFlagsMerge() {
        val s = SegmentPlanner.auto(0, 20_000, emptyList(), listOf(6_000, 8_000, 10_000))
        assertEquals(listOf(CropMode.CENTER, CropMode.FIT_BLUR, CropMode.CENTER), s.map { it.mode })
        assertEquals(5_000, s[1].startMs); assertEquals(11_000, s[1].endMs)
    }

    @Test fun flagAtEndRunsToClipEnd() {
        val s = SegmentPlanner.auto(0, 12_000, emptyList(), listOf(11_500))
        assertEquals(CropMode.FIT_BLUR, s.last().mode)
        assertEquals(12_000, s.last().endMs)
    }

    @Test fun normalizeTilesAndAbsorbsTinySegments() {
        val raw = listOf(Segment(0, 5_000, CropMode.CENTER), Segment(5_000, 5_200, CropMode.FIT_BLUR), Segment(5_300, 9_000, CropMode.PAN, panX = 0.5f))
        val n = SegmentPlanner.normalize(raw, 1_000, 10_000)
        assertEquals(1_000, n.first().startMs)
        assertEquals(10_000, n.last().endMs)
        n.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        assertFalse(n.any { it.mode == CropMode.FIT_BLUR }) // 200 ms segment folded away
    }

    @Test fun normalizeMergesIdenticalNeighbours() {
        val n = SegmentPlanner.normalize(listOf(Segment(0, 3_000, CropMode.CENTER), Segment(3_000, 6_000, CropMode.CENTER)), 0, 6_000)
        assertEquals(1, n.size)
    }

    @Test fun splitAndMerge() {
        val one = listOf(Segment(0, 10_000, CropMode.CENTER))
        val two = SegmentPlanner.splitAt(one, 4_000)
        assertEquals(listOf(4_000L, 6_000L), two.map { it.lengthMs })
        assertEquals(one, SegmentPlanner.splitAt(one, 200)) // too close to an edge
        assertEquals(1, SegmentPlanner.indexAt(two, 4_000))
        assertEquals(one, SegmentPlanner.mergeWithNext(two, 0))
    }

    @Test fun jsonRoundTrip() {
        val segs = listOf(Segment(0, 1_000, CropMode.ZOOM_FIT, zoomRect = NRect(0.1f, 0.1f, 0.6f, 0.6f)))
        assertEquals(segs, SegmentPlanner.decode(SegmentPlanner.encode(segs)))
        assertNull(SegmentPlanner.decode("garbage"))
    }
}

class ExportMathTest {
    @Test fun fadeRamps() {
        val total = 48_000L // 1 s at 48 kHz
        val fin = 3_840L    // 0.08 s
        val fout = 19_200L  // 0.4 s
        assertEquals(0f, ExportMath.fadeGain(0, total, fin, fout), 1e-6f)
        assertEquals(0.5f, ExportMath.fadeGain(1_920, total, fin, fout), 1e-3f)
        assertEquals(1f, ExportMath.fadeGain(20_000, total, fin, fout), 1e-6f)
        assertEquals(0.5f, ExportMath.fadeGain(total - 1 - 9_600, total, fin, fout), 1e-3f)
        assertEquals(0f, ExportMath.fadeGain(total - 1, total, fin, fout), 1e-6f)
    }

    @Test fun fadesScaleDownOnVeryShortItems() {
        val g = ExportMath.fadeGain(100, 1_000, 3_840, 19_200)
        assertTrue(g in 0f..1f)
    }

    @Test fun noFadeMeansUnityGain() = assertEquals(1f, ExportMath.fadeGain(0, 1_000, 0, 0), 0f)

    @Test fun slugsAndFileNames() {
        assertEquals("01_insane_1v4_clutch.mp4", ExportMath.fileName(1, "INSANE 1v4 Clutch!!"))
        assertEquals("12_cafe_noel.mp4", ExportMath.fileName(12, "Café Noël"))
        assertEquals("03_clip.mp4", ExportMath.fileName(3, "🔥🔥🔥"))
        assertTrue(ExportMath.slug("a".repeat(100)).length <= 40)
    }

    @Test fun outputFpsNeverExceedsSource() {
        assertEquals(60, ExportMath.outputFps(59.94f, 60))
        assertEquals(30, ExportMath.outputFps(60f, 30))
        assertEquals(30, ExportMath.outputFps(29.97f, 60))
        assertEquals(60, ExportMath.outputFps(0f, 60))
        assertEquals(60, ExportMath.outputFps(120f, 60))
    }

    @Test fun trailingBlackTrim() {
        // Black from 14.0 s to the 15.0 s end.
        val end = ExportMath.trimTrailingBlack(5_000, 15_000, isBlackAt = { it >= 14_000 })
        assertTrue(end in 14_000..14_100)
        assertEquals(15_000, ExportMath.trimTrailingBlack(5_000, 15_000, isBlackAt = { false }))
        // Never trims below the minimum length.
        assertEquals(8_000, ExportMath.trimTrailingBlack(5_000, 15_000, isBlackAt = { true }, maxTrimMs = 60_000))
    }
}

class Mp4FastStartTest {
    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); val d = DataOutputStream(out)
        d.writeInt(8 + body.size); d.writeBytes(type); d.write(body); return out.toByteArray()
    }
    private fun stco(offsets: List<Int>): ByteArray {
        val out = ByteArrayOutputStream(); val d = DataOutputStream(out)
        d.writeInt(0); d.writeInt(offsets.size); offsets.forEach { d.writeInt(it) }
        return box("stco", out.toByteArray())
    }
    private fun moov(offsets: List<Int>) =
        box("moov", box("trak", box("mdia", box("minf", box("stbl", stco(offsets))))))

    @Test fun movesMoovFrontAndShiftsChunkOffsets() {
        val ftyp = box("ftyp", "isom\u0000\u0000\u0002\u0000isom".toByteArray(Charsets.ISO_8859_1))
        val payload = ByteArray(100) { it.toByte() }
        val mdat = box("mdat", payload)
        val mdatDataStart = ftyp.size + 8
        val chunks = listOf(mdatDataStart, mdatDataStart + 40)
        val moov = moov(chunks)
        val src = File.createTempFile("cf_in", ".mp4").apply { writeBytes(ftyp + mdat + moov) }
        val dst = File.createTempFile("cf_out", ".mp4")
        try {
            assertFalse(Mp4FastStart.isFastStart(src))
            assertFalse(Mp4FastStart.ensure(src, dst)) // rewrote
            assertTrue(Mp4FastStart.isFastStart(dst))
            val bytes = dst.readBytes()
            assertEquals(src.length(), dst.length())
            // Read patched offsets back and check they still point at the same payload bytes.
            val bb = ByteBuffer.wrap(bytes)
            val stcoAt = String(bytes, Charsets.ISO_8859_1).indexOf("stco")
            val n = bb.getInt(stcoAt + 8)
            assertEquals(2, n)
            val o1 = bb.getInt(stcoAt + 12); val o2 = bb.getInt(stcoAt + 16)
            assertEquals(chunks[0] + moov.size, o1)
            assertEquals(payload[0], bytes[o1]); assertEquals(payload[40], bytes[o2])
        } finally {
            src.delete(); dst.delete()
        }
    }

    @Test fun alreadyFastStartIsLeftAlone() {
        val ftyp = box("ftyp", ByteArray(8))
        val src = File.createTempFile("cf_in", ".mp4").apply { writeBytes(ftyp + moov(listOf(100)) + box("mdat", ByteArray(10))) }
        val dst = File.createTempFile("cf_out", ".mp4")
        try {
            assertTrue(Mp4FastStart.ensure(src, dst))
            assertEquals(0L, dst.length())
        } finally {
            src.delete(); dst.delete()
        }
    }
}
