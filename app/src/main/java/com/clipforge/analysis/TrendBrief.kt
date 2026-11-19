package com.clipforge.analysis

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class TrendVideo(
    val title: String,
    val channel: String,
    val views: Long,
    val likes: Long? = null,
    val comments: Long? = null,
    val published_at: String = "",
    val duration_s: Double = 0.0,
    val url: String = "",
)

@Serializable
data class TrendsResponse(
    val game: String,
    val source: String,
    val window_days: Int,
    val fetched_at: String,
    val videos: List<TrendVideo>,
)

enum class TrendSource { YOUTUBE, BUILT_IN }

/** Text the model sees about current trends, plus what the UI reports as the source. */
data class TrendBrief(val source: TrendSource, val fullText: String, val shortHints: String?, val note: String) {
    companion object {
        fun compactViews(v: Long): String = when {
            v >= 1_000_000 -> String.format(Locale.US, "%.1fM", v / 1_000_000.0)
            v >= 1_000 -> String.format(Locale.US, "%.0fK", v / 1_000.0)
            else -> v.toString()
        }

        fun fromYoutube(r: TrendsResponse): TrendBrief {
            if (r.videos.isEmpty()) return builtIn("YouTube returned no recent Shorts for \"${r.game}\"")
            val lines = r.videos.take(20).mapIndexed { i, v ->
                "${i + 1}. \"${v.title.replace('\n', ' ').take(120)}\" - ${compactViews(v.views)} views, ${v.duration_s.toInt()} s"
            }
            val full = "Most-viewed YouTube Shorts for ${r.game} published in the last ${r.window_days} days:\n" + lines.joinToString("\n")
            val short = lines.take(8).joinToString("\n")
            return TrendBrief(TrendSource.YOUTUBE, full, short, "Live: top ${r.videos.size} Shorts from the last ${r.window_days} days")
        }

        fun builtIn(reason: String): TrendBrief = TrendBrief(
            TrendSource.BUILT_IN,
            "No live trend data is available ($reason). Use your general knowledge of what performs well in gaming Shorts.",
            null,
            "Built-in knowledge ($reason)",
        )
    }
}
