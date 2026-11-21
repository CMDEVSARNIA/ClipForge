package com.clipforge

import com.clipforge.analysis.DecodeBudget
import com.clipforge.analysis.SamplePlan
import com.clipforge.captions.CpuFeatures
import com.clipforge.util.PerfFormat
import com.clipforge.util.PerfLog
import com.clipforge.util.PerfRun
import com.clipforge.util.PerfStage
import com.clipforge.util.PerfTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class SpeedTest {
    // Keyframes every 1 s: nearest sync is the rounded second.
    private val everySecond: (Double) -> Double? = { t -> Math.round(t).toDouble() }

    @Test fun planUsesNearbyKeyframes() {
        val plan = SamplePlan.plan(10.0, 2.0, { t -> t + 0.4 })
        assertEquals(5, plan.size)
        assertTrue(plan.all { it.keyframe })
        assertEquals(listOf(0.4, 2.4, 4.4, 6.4, 8.4), plan.map { it.timeS })
    }

    @Test fun planFallsBackWhenKeyframeTooFar() {
        // Keyframes only every 10 s: every target except 0 is more than 0.9 s from one.
        val plan = SamplePlan.plan(10.0, 2.0, { t -> Math.round(t / 10) * 10.0 })
        assertEquals(listOf(true, false, false, false, false), plan.map { it.keyframe })
        assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0), plan.map { it.timeS })
    }

    @Test fun planNeverReusesAKeyframe() {
        // Interval shorter than the tolerance: two targets would snap to the same keyframe.
        val plan = SamplePlan.plan(4.0, 0.5, everySecond)
        val keyTimes = plan.filter { it.keyframe }.map { it.timeS }
        assertEquals(keyTimes.size, keyTimes.toSet().size)
        assertEquals(plan.size, plan.map { Math.round(it.timeS * 1000) }.toSet().size)
        assertEquals(plan.sortedBy { it.timeS }, plan)
    }

    @Test fun planWithoutSyncInfoKeepsExactTimes() {
        val plan = SamplePlan.plan(6.0, 2.0, { null })
        assertEquals(listOf(0.0, 2.0, 4.0), plan.map { it.timeS })
        assertFalse(plan.any { it.keyframe })
    }

    @Test fun planIgnoresKeyframesPastTheEnd() {
        val plan = SamplePlan.plan(5.0, 2.0, { t -> t + 0.8 })
        assertTrue(plan.all { it.timeS < 5.0 })
        assertTrue(plan.all { abs(it.timeS - Math.round(it.timeS)) < 1.0 })
    }

    @Test fun planRemembersFarKeyframesForFastScan() {
        // Keyframes every 10 s: targets at 2–8 s are exact grabs but keep the keyframe they'd fall back to.
        val plan = SamplePlan.plan(20.0, 2.0, { t -> Math.round(t / 10) * 10.0 })
        val t4 = plan.first { it.timeS == 4.0 }
        assertFalse(t4.keyframe)
        assertEquals(0.0, t4.syncS!!, 1e-9)
        assertEquals(10.0, plan.first { it.timeS == 8.0 }.syncS!!, 1e-9)
        assertEquals(10.0, SamplePlan.medianKeyframeGapS(plan)!!, 1e-9)
        assertEquals(null, SamplePlan.medianKeyframeGapS(SamplePlan.plan(6.0, 2.0, { null })))
    }

    @Test fun decodeBudgetSwitchesToFastScanOnlyWhenSlow() {
        val quick = DecodeBudget(thresholdMs = 350, minSamples = 6)
        repeat(20) { quick.record(120) }
        assertFalse(quick.fastScan)
        assertEquals(120L, quick.averageMs)

        val slow = DecodeBudget(thresholdMs = 350, minSamples = 6)
        repeat(5) { slow.record(2_000) }
        assertFalse("needs a few samples before deciding", slow.fastScan)
        slow.record(2_000)
        assertTrue(slow.fastScan)
        assertEquals(6, slow.exactFrames)
    }

    @Test fun shortVideoStillGetsAFrame() {
        assertEquals(1, SamplePlan.plan(0.05, 2.0, { null }).size)
    }

    @Test fun chunksSplitEvenlyAndKeepOrder() {
        val items = (1..10).toList()
        val parts = SamplePlan.chunks(items, 3)
        assertEquals(3, parts.size)
        assertEquals(items, parts.flatten())
        assertEquals(listOf(listOf(1), listOf(2)), SamplePlan.chunks(listOf(1, 2), 3))
        assertTrue(SamplePlan.chunks(emptyList<Int>(), 3).isEmpty())
    }

    private val modernCpu = """
        processor	: 0
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
        processor	: 7
        Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm lrcpc dcpop asimddp
    """.trimIndent()

    @Test fun fastWhisperOnModernCpu() {
        assertTrue(CpuFeatures.supportsFastWhisper(modernCpu, listOf("arm64-v8a", "armeabi-v7a")))
    }

    @Test fun compatibleWhisperOnOlderOrMixedCpu() {
        val a53 = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid\n"
        assertFalse(CpuFeatures.supportsFastWhisper(a53, listOf("arm64-v8a")))
        val mixed = modernCpu + "\nprocessor\t: 8\nFeatures\t: fp asimd evtstrm aes cpuid\n"
        assertFalse(CpuFeatures.supportsFastWhisper(mixed, listOf("arm64-v8a")))
        assertFalse(CpuFeatures.supportsFastWhisper(modernCpu, listOf("x86_64")))
        assertFalse(CpuFeatures.supportsFastWhisper("", listOf("arm64-v8a")))
    }

    @Test fun perfLineShowsRealtimeRatio() {
        assertEquals("Ranking · 3.2 s", PerfFormat.line(PerfStage("Ranking", 3200)))
        assertEquals("Export clip 1 · 18.0 s for 30 s of video (0.60× realtime)", PerfFormat.line(PerfStage("Export clip 1", 18000, 30.0)))
    }

    @Test fun timerMarksIntervals() {
        var now = 1000L
        val t = PerfTimer { now }
        now = 1500; t.mark("a")
        now = 1600; t.restart()
        now = 2600; t.mark("b", 4.0)
        val run = t.run("Export")
        assertEquals(listOf(500L, 1000L), run.stages.map { it.ms })
        assertEquals(1500L, run.totalMs)
    }

    @Test fun perfLogKeepsLatestPerKind() {
        val f = File.createTempFile("perf", ".json").also { it.delete() }
        val log = PerfLog(f)
        assertTrue(log.all().isEmpty())
        log.save(PerfRun("Export", 1, listOf(PerfStage("x", 1))))
        log.save(PerfRun("Analysis", 2, listOf(PerfStage("y", 2))))
        log.save(PerfRun("Export", 3, listOf(PerfStage("z", 3))))
        log.save(PerfRun("Captions", 4, emptyList())) // empty runs are not saved
        assertEquals(listOf("Analysis", "Export"), log.all().map { it.kind })
        assertEquals("z", log.all().last().stages.single().name)
        f.delete()
    }
}
