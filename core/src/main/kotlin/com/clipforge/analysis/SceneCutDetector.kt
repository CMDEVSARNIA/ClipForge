package com.clipforge.analysis

import kotlin.math.abs

/**
 * Compact per-frame fingerprint: a 32×18 luma grid plus a 4×4×4 RGB histogram.
 * Built from a tiny downscaled frame, so thousands of them fit in a couple of MB.
 */
class FrameSignature(val luma: ByteArray, val hist: FloatArray, val meanLuma: Float) {
    companion object {
        const val W = 32
        const val H = 18

        /** @param argb pixels of a [W]×[H] image, row-major. */
        fun fromArgb(argb: IntArray): FrameSignature {
            require(argb.size == W * H) { "expected ${W * H} pixels" }
            val luma = ByteArray(argb.size)
            val hist = FloatArray(64)
            var sum = 0L
            for (i in argb.indices) {
                val p = argb[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val y = (r * 299 + g * 587 + b * 114) / 1000
                luma[i] = y.toByte()
                sum += y
                hist[(r shr 6) * 16 + (g shr 6) * 4 + (b shr 6)] += 1f
            }
            for (i in hist.indices) hist[i] /= argb.size.toFloat()
            return FrameSignature(luma, hist, sum.toFloat() / argb.size)
        }
    }

    /** 0 (identical) … 1 (completely different). Mixes structure (luma) and color distribution. */
    fun distance(other: FrameSignature): Double {
        var lumaDiff = 0L
        for (i in luma.indices) lumaDiff += abs((luma[i].toInt() and 0xFF) - (other.luma[i].toInt() and 0xFF))
        val lumaMad = lumaDiff.toDouble() / luma.size / 255.0
        var histL1 = 0.0
        for (i in hist.indices) histL1 += abs(hist[i] - other.hist[i])
        return 0.6 * (histL1 / 2.0) + 0.4 * lumaMad
    }

    val isBlack: Boolean get() = meanLuma < 12f
}

object SceneCutDetector {
    data class Config(
        /** Distance that is a cut regardless of context. */
        val absoluteThreshold: Double = 0.38,
        /** Lower bound for adaptive detection. */
        val minThreshold: Double = 0.18,
        /** A distance this many times above the local median is a cut (handles busy gameplay). */
        val relativeFactor: Double = 3.0,
        val localWindow: Int = 8,
    )

    /**
     * @param times sample timestamps (seconds), ascending.
     * @return cuts placed at the midpoint between the two samples that straddle them (refine later).
     */
    fun detect(times: DoubleArray, sigs: List<FrameSignature>, cfg: Config = Config()): List<SceneCut> {
        require(times.size == sigs.size)
        if (sigs.size < 2) return emptyList()
        val d = DoubleArray(sigs.size) { i -> if (i == 0) 0.0 else sigs[i].distance(sigs[i - 1]) }
        val cuts = ArrayList<SceneCut>()
        for (i in 1 until d.size) {
            val lo = (i - cfg.localWindow).coerceAtLeast(1)
            val hi = (i + cfg.localWindow).coerceAtMost(d.size - 1)
            val neighborhood = (lo..hi).filter { it != i }.map { d[it] }.sorted()
            val median = if (neighborhood.isEmpty()) 0.0 else neighborhood[neighborhood.size / 2]
            val isCut = d[i] >= cfg.absoluteThreshold ||
                (d[i] >= cfg.minThreshold && d[i] >= median * cfg.relativeFactor)
            if (isCut) cuts += SceneCut((times[i - 1] + times[i]) / 2.0, d[i], afterIndex = i)
        }
        return cuts
    }
}
