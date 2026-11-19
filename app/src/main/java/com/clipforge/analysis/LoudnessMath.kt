package com.clipforge.analysis

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

object LoudnessMath {
    const val WINDOW_S = 0.5

    data class Peak(val timeS: Double, val db: Float, val aboveMedianDb: Float)

    fun rmsDb(sumSquares: Double, count: Long): Float {
        if (count <= 0) return -96f
        val rms = sqrt(sumSquares / count)
        return (20.0 * log10(max(rms, 1e-5))).toFloat().coerceAtLeast(-96f)
    }

    fun median(values: FloatArray): Float {
        if (values.isEmpty()) return -96f
        val s = values.sortedArray()
        return s[s.size / 2]
    }

    /**
     * Loud moments relative to the video's own baseline: local maxima at least [minAboveMedianDb]
     * over the median, greedily picked by loudness with [minSeparationS] spacing.
     */
    fun findPeaks(
        db: FloatArray,
        windowS: Double = WINDOW_S,
        minAboveMedianDb: Float = 6f,
        minSeparationS: Double = 4.0,
        maxPeaks: Int = 400,
    ): List<Peak> {
        if (db.size < 3) return emptyList()
        val med = median(db)
        val candidates = ArrayList<Int>()
        for (i in db.indices) {
            val v = db[i]
            if (v - med < minAboveMedianDb) continue
            val l = (i - 2).coerceAtLeast(0)
            val r = (i + 2).coerceAtMost(db.size - 1)
            var isMax = true
            for (j in l..r) if (j != i && db[j] > v) { isMax = false; break }
            if (isMax) candidates += i
        }
        val picked = ArrayList<Int>()
        val sep = (minSeparationS / windowS).toInt()
        for (i in candidates.sortedByDescending { db[it] }) {
            if (picked.size >= maxPeaks) break
            if (picked.none { kotlin.math.abs(it - i) < sep }) picked += i
        }
        return picked.sorted().map { Peak(it * windowS + windowS / 2, db[it], db[it] - med) }
    }

    /** Max loudness above median inside [startS, endS); 0 when there is no audio data. */
    fun maxAboveMedian(db: FloatArray, startS: Double, endS: Double, windowS: Double = WINDOW_S): Float {
        if (db.isEmpty()) return 0f
        val med = median(db)
        val a = (startS / windowS).toInt().coerceIn(0, db.size - 1)
        val b = (endS / windowS).toInt().coerceIn(a, db.size - 1)
        var m = -96f
        for (i in a..b) m = max(m, db[i])
        return (m - med).coerceAtLeast(0f)
    }
}
