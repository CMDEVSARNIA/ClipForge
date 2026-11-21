package com.clipforge.util

import java.util.Locale

object TimeFormat {
    /**
     * Formats milliseconds as m:ss, or h:mm:ss for an hour or more. With [tenths] a decimal is added
     * (e.g. "12:34.5"), useful while scrubbing.
     */
    fun format(ms: Long, tenths: Boolean = false): String {
        val clamped = ms.coerceAtLeast(0)
        val totalSec = clamped / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val base = if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
        return if (tenths) "$base.${(clamped % 1000) / 100}" else base
    }
}
