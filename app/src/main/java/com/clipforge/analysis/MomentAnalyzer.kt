package com.clipforge.analysis

import android.content.Context
import android.util.Base64
import androidx.core.net.toUri
import com.clipforge.data.db.AnalysisDao
import com.clipforge.data.db.AnalysisEntity
import com.clipforge.data.db.ClipDao
import com.clipforge.data.db.ClipEntity
import com.clipforge.data.db.ProjectDao
import com.clipforge.net.ModelRefusedException
import com.clipforge.net.WorkerClient
import com.clipforge.net.WorkerException
import com.clipforge.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

class AnalysisException(message: String) : Exception(message)

/**
 * Moment-finding pipeline:
 *  1. loudness timeline (0.5 s RMS)   2. frames every 2 s + signatures   3. scene cuts
 *  4. game id (if not given)          5. trend brief (YouTube via Worker, else built-in)
 *  6. per-batch frame analysis (≤40 frames/request, analysis model)
 *  7. dedupe, refine cuts near edges, snap edges        8. global ranking (writing model)
 *  9. persist clips + signals
 */
class MomentAnalyzer(
    private val context: Context,
    private val projects: ProjectDao,
    private val analyses: AnalysisDao,
    private val clips: ClipDao,
    private val worker: WorkerClient,
    private val settings: SettingsRepository,
    private val signals: SignalsStore,
) {
    data class Progress(val fraction: Float, val step: String)

    private val audio = AudioLoudnessAnalyzer(context)
    private val sampler = FrameSampler(context)
    private val refiner = CutRefiner(context)

    private class Cost {
        @Volatile var usd = 0.0
        @Synchronized fun add(v: Double) { usd += v }
    }

    suspend fun run(projectId: Long, gameHint: String?, report: (Progress) -> Unit): Long {
        val project = projects.get(projectId) ?: throw AnalysisException("Project not found.")
        val s = settings.current()
        if (!s.isConnected) throw AnalysisException("Connect ClipForge to your Worker in Settings first.")
        val info = project.toVideoInfo()
        val uri = project.sourceUri.toUri()
        val durationS = info.durationMs / 1000.0
        val hint = gameHint?.trim()?.takeIf { it.isNotEmpty() }
        val estimate = CostEstimator.estimate(durationS, info.displayWidth, info.displayHeight, s.analysisModel, s.writingModel, hint == null)

        val now = System.currentTimeMillis()
        analyses.markStaleRunsFailed(projectId, now)
        var a = AnalysisEntity(
            projectId = projectId, status = "RUNNING", game = hint, gameDetected = false, trendSource = null,
            trendNote = null, trendSummary = null, analysisModel = s.analysisModel, rankModel = s.writingModel,
            estimatedCostUsd = estimate.totalUsd, createdAt = now,
        )
        a = a.copy(id = analyses.insert(a))
        val cost = Cost()
        val frameDir = File(context.cacheDir, "analysis/$projectId")

        try {
            // 1. Loudness
            report(Progress(0f, "Measuring loudness"))
            val db = if (info.hasAudio) audio.analyze(uri, info.durationMs) { p -> report(Progress(0.12f * p, "Measuring loudness")) } else FloatArray(0)
            val peaks = LoudnessMath.findPeaks(db)

            // 2. Frames
            val samples = sampler.sample(uri, info.durationMs, info.displayWidth, info.displayHeight, frameDir) { done, total ->
                report(Progress(0.12f + 0.43f * done / total, "Sampling frames $done/$total"))
            }
            if (samples.size < 2) throw AnalysisException("Couldn't read frames from this video.")
            val times = DoubleArray(samples.size) { samples[it].timeS }
            val coarseCuts = SceneCutDetector.detect(times, samples.map { it.sig })

            // 3. Game
            var game = hint
            var detected = false
            if (game == null) {
                report(Progress(0.56f, "Identifying the game"))
                game = detectGame(samples, s.analysisModel, cost)
                detected = game != null
            }

            // 4. Trends
            report(Progress(0.58f, "Checking what's trending"))
            val brief = trendBrief(game, s.useTrends)
            a = a.copy(game = game, gameDetected = detected, trendSource = brief.source.name, trendNote = brief.note, framesSampled = samples.size)
            analyses.update(a)

            // 5. Batches
            val plan = CostEstimator.planBatches(samples.size)
            val done = AtomicInteger(0)
            val failed = AtomicInteger(0)
            val gate = Semaphore(3)
            report(Progress(0.6f, "Analyzing frames 0/${plan.size}"))
            val outcomes = coroutineScope {
                plan.map { range ->
                    async {
                        gate.withPermit {
                            val r = analyzeBatch(range, samples, durationS, game, peaks, coarseCuts, brief.shortHints, info.hasAudio, s.analysisModel, cost)
                            if (r == null) failed.incrementAndGet()
                            val n = done.incrementAndGet()
                            report(Progress(0.6f + 0.28f * n / plan.size, "Analyzing frames $n/${plan.size}"))
                            r
                        }
                    }
                }.awaitAll()
            }
            if (failed.get() == plan.size) throw AnalysisException("Every analysis request failed. Check your connection and Worker logs.")
            val candidatesRaw = outcomes.filterNotNull().flatMap { it.candidates }
            val specialFrames = outcomes.filterNotNull().flatMap { it.specialFrames }.distinctBy { it.tS to it.kind }

            // 6. Dedupe, refine cuts around candidates, snap
            report(Progress(0.89f, "Snapping to scene cuts"))
            val deduped = ClipSnapper.dedupe(candidatesRaw).sortedByDescending { it.score }.take(CostEstimator.MAX_RANKED_CANDIDATES)
            val cutTimes = refineCuts(uri, coarseCuts, samples, deduped)
            val snapCfg = ClipSnapper.Config(cutGuardS = 0.15)
            val snapped = deduped.map { c ->
                val sn = ClipSnapper.snap(c.startS, c.endS, cutTimes, durationS, snapCfg)
                c.copy(startS = sn.startS, endS = sn.endS)
            }.filter { it.lengthS >= 3.0 }

            signals.write(
                projectId,
                AnalysisSignals(
                    sampleIntervalS = CostEstimator.SAMPLE_INTERVAL_S,
                    loudnessWindowS = LoudnessMath.WINDOW_S,
                    loudnessDb = db.toList(),
                    cuts = cutTimes,
                    specialFrames = specialFrames,
                    blackFrames = samples.filter { it.sig.isBlack }.map { it.timeS },
                )
            )

            // 7. Rank
            val warning = buildList {
                if (failed.get() > 0) add("${failed.get()} of ${plan.size} analysis requests failed; those parts of the video were skipped.")
                if (snapped.isEmpty()) add("No clip-worthy moments were found.")
            }.joinToString(" ").ifBlank { null }

            val (ranking, rankNote) = if (snapped.isEmpty()) RankingResult(emptyList(), "") to null else {
                report(Progress(0.92f, "Ranking ${snapped.size} moments"))
                rank(snapped, samples, db, brief, game, s.writingModel, cost)
            }

            // 8. Persist
            val entities = toEntities(projectId, a.id, snapped, ranking, samples, db)
            clips.replaceForProject(projectId, entities)
            a = a.copy(
                status = "DONE",
                trendSummary = ranking.trendSummary.ifBlank { null },
                actualCostUsd = cost.usd,
                batchesFailed = failed.get(),
                warning = listOfNotNull(warning, rankNote).joinToString(" ").ifBlank { null },
                finishedAt = System.currentTimeMillis(),
            )
            analyses.update(a)
            frameDir.deleteRecursively()
            report(Progress(1f, "Done"))
            return a.id
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                analyses.update(a.copy(status = "CANCELLED", actualCostUsd = cost.usd, finishedAt = System.currentTimeMillis()))
            }
            throw e
        } catch (e: Exception) {
            val msg = when (e) {
                is WorkerException -> e.message ?: "Worker error"
                is IOException -> "Network error: ${e.message ?: "connection failed"}"
                else -> e.message ?: e::class.simpleName ?: "Unknown error"
            }
            withContext(NonCancellable) {
                analyses.update(a.copy(status = "FAILED", error = msg, actualCostUsd = cost.usd, finishedAt = System.currentTimeMillis()))
            }
            throw AnalysisException(msg)
        }
    }

    private suspend fun b64(file: File): String = withContext(Dispatchers.IO) {
        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
    }

    /** Errors that would make every request fail: stop the whole run instead of skipping batches. */
    private fun isFatal(e: WorkerException) =
        e.status == 401 || e.status == 403 || e.type == "budget_exceeded" || e.type == "invalid_request_error" || e.type == "not_connected"

    private suspend fun detectGame(samples: List<FrameSampler.Sample>, model: String, cost: Cost): String? {
        val picks = listOf(0.2, 0.4, 0.6, 0.8).map { samples[(it * (samples.size - 1)).toInt()] }.distinct()
        val content = ArrayList<JsonObject>()
        content += ClaudeRequests.text("Frames from one gameplay recording. Which game is it?")
        picks.forEach { content += ClaudeRequests.jpeg(b64(it.file)) }
        val req = ClaudeRequests.structured(model, Prompts.GAME_SYSTEM, content, Prompts.GAME_SCHEMA, 1000, "low")
        return try {
            val reply = worker.messages(req)
            cost.add(reply.costUsd)
            MomentParser.parseGame(reply.text)?.takeIf { it.second >= 0.4 }?.first
        } catch (e: WorkerException) {
            if (isFatal(e)) throw e
            null
        } catch (_: ModelRefusedException) {
            null
        }
    }

    private suspend fun trendBrief(game: String?, useTrends: Boolean): TrendBrief {
        if (!useTrends) return TrendBrief.builtIn("live trends are off in Settings")
        if (game == null) return TrendBrief.builtIn("the game wasn't identified")
        return try {
            TrendBrief.fromYoutube(worker.trends(game))
        } catch (e: WorkerException) {
            TrendBrief.builtIn(if (e.type == "trends_unavailable") "YouTube trends aren't set up on the Worker" else (e.message ?: "trend lookup failed"))
        } catch (_: IOException) {
            TrendBrief.builtIn("trend lookup failed (network)")
        } catch (_: kotlinx.serialization.SerializationException) {
            TrendBrief.builtIn("unexpected trend response")
        }
    }

    private suspend fun analyzeBatch(
        range: IntRange,
        samples: List<FrameSampler.Sample>,
        durationS: Double,
        game: String?,
        peaks: List<LoudnessMath.Peak>,
        cuts: List<SceneCut>,
        trendHints: String?,
        hasAudio: Boolean,
        model: String,
        cost: Cost,
    ): BatchResult? {
        val t0 = samples[range.first].timeS
        val t1 = (samples[range.last].timeS + CostEstimator.SAMPLE_INTERVAL_S).coerceAtMost(durationS)
        val content = ArrayList<JsonObject>(range.count() * 2 + 1)
        content += ClaudeRequests.text(
            Prompts.batchHeader(
                game, durationS, t0, t1,
                peaks.filter { it.timeS in (t0 - 1)..(t1 + 1) },
                cuts.filter { it.timeS in t0..t1 },
                trendHints, hasAudio,
            )
        )
        for (i in range) {
            content += ClaudeRequests.text("t=${Prompts.fmt(samples[i].timeS)}s")
            content += ClaudeRequests.jpeg(b64(samples[i].file))
        }
        var maxTokens = 4000
        repeat(2) { attempt ->
            try {
                val reply = worker.messages(ClaudeRequests.structured(model, Prompts.BATCH_SYSTEM, content, Prompts.BATCH_SCHEMA, maxTokens, "low"))
                cost.add(reply.costUsd)
                if (reply.stopReason == "max_tokens") throw InvalidModelOutput("Response was cut off")
                return MomentParser.parseBatch(reply.text, t0, t1, durationS)
            } catch (e: InvalidModelOutput) {
                // Retry once, with more room in case the output was truncated.
                maxTokens = 8000
                if (attempt == 1) return null
            } catch (e: WorkerException) {
                if (isFatal(e)) throw e
                return null
            } catch (_: ModelRefusedException) {
                return null
            } catch (_: IOException) {
                return null
            }
        }
        return null
    }

    /** Refines only the cuts that matter: those inside or near a candidate clip. */
    private suspend fun refineCuts(
        uri: android.net.Uri,
        coarse: List<SceneCut>,
        samples: List<FrameSampler.Sample>,
        candidates: List<MomentCandidate>,
    ): List<Double> {
        val (near, far) = coarse.partition { c ->
            c.afterIndex > 0 && candidates.any { c.timeS >= it.startS - 3.5 && c.timeS <= it.endS + 2.5 }
        }
        val refined = refiner.refine(uri, near.map { c ->
            val before = samples[c.afterIndex - 1]
            val after = samples[c.afterIndex]
            Triple(before.timeS, after.timeS, before.sig to after.sig)
        })
        return (refined + far.map { it.timeS }).sorted()
    }

    private suspend fun rank(
        candidates: List<MomentCandidate>,
        samples: List<FrameSampler.Sample>,
        db: FloatArray,
        brief: TrendBrief,
        game: String?,
        model: String,
        cost: Cost,
    ): Pair<RankingResult, String?> {
        val content = ArrayList<JsonObject>()
        content += ClaudeRequests.text(buildString {
            appendLine("Game: ${game ?: "unknown"}")
            appendLine()
            appendLine(brief.fullText)
            appendLine()
            append("${candidates.size} candidates follow, each with one representative frame.")
        })
        candidates.forEachIndexed { i, c ->
            val loud = LoudnessMath.maxAboveMedian(db, c.startS, c.endS)
            content += ClaudeRequests.text(
                "Candidate ${i + 1}: ${Prompts.fmt(c.startS)}-${Prompts.fmt(c.endS)} s (${c.lengthS.toInt()} s). " +
                    (if (db.isEmpty()) "No audio data. " else "Loudness peak +${loud.toInt()} dB over median. ") +
                    "First pass ${Prompts.fmt(c.score)}/10: \"${c.title}\" - ${c.reason}"
            )
            content += ClaudeRequests.jpeg(b64(representativeFrame(c, samples, db).file))
        }
        val ids = (1..candidates.size).toSet()
        repeat(2) { attempt ->
            try {
                val reply = worker.messages(ClaudeRequests.structured(model, Prompts.RANK_SYSTEM, content, Prompts.RANK_SCHEMA, 16000, "medium"))
                cost.add(reply.costUsd)
                if (reply.stopReason == "max_tokens") throw InvalidModelOutput("Response was cut off")
                return MomentParser.parseRanking(reply.text, ids) to null
            } catch (e: InvalidModelOutput) {
                if (attempt == 1) return fallbackRanking(candidates) to "Ranking pass returned invalid output; clips are ordered by first-pass scores."
            } catch (e: WorkerException) {
                if (isFatal(e)) throw e
                return fallbackRanking(candidates) to "Ranking pass failed (${e.message}); clips are ordered by first-pass scores."
            } catch (e: ModelRefusedException) {
                return fallbackRanking(candidates) to "Ranking pass was declined; clips are ordered by first-pass scores."
            } catch (_: IOException) {
                return fallbackRanking(candidates) to "Ranking pass failed (network); clips are ordered by first-pass scores."
            }
        }
        return fallbackRanking(candidates) to null
    }

    private fun fallbackRanking(candidates: List<MomentCandidate>) = RankingResult(
        candidates.mapIndexed { i, c ->
            val score = (c.score * 10).toInt().coerceIn(0, 100)
            RankedMoment(i + 1, Tier.fromScore(score), score, null, c.title, c.hashtags, c.reason)
        }.sortedByDescending { it.score },
        "",
    )

    private fun representativeFrame(c: MomentCandidate, samples: List<FrameSampler.Sample>, db: FloatArray): FrameSampler.Sample {
        var target = (c.startS + c.endS) / 2
        if (db.isNotEmpty()) {
            val a = (c.startS / LoudnessMath.WINDOW_S).toInt().coerceIn(0, db.size - 1)
            val b = (c.endS / LoudnessMath.WINDOW_S).toInt().coerceIn(a, db.size - 1)
            val loudest = (a..b).maxBy { db[it] }
            target = loudest * LoudnessMath.WINDOW_S
        }
        return samples.minBy { abs(it.timeS - target) }
    }

    private fun toEntities(
        projectId: Long,
        analysisId: Long,
        candidates: List<MomentCandidate>,
        ranking: RankingResult,
        samples: List<FrameSampler.Sample>,
        db: FloatArray,
    ): List<ClipEntity> {
        val byId = ranking.ranked.associateBy { it.candidateId }
        val merged = candidates.mapIndexed { i, c ->
            val r = byId[i + 1]
            val score = r?.score ?: (c.score * 10).toInt().coerceIn(0, 100)
            Triple(c, r, score)
        }.sortedByDescending { it.third }
        return merged.mapIndexed { rank, (c, r, score) ->
            val startMs = (c.startS * 1000).toLong()
            val endMs = (c.endS * 1000).toLong()
            val thumbT = (c.startS + 1.5).coerceAtMost(c.endS)
            ClipEntity(
                projectId = projectId,
                analysisId = analysisId,
                rank = rank + 1,
                tier = (r?.tier ?: Tier.fromScore(score)).name,
                score = score,
                startMs = startMs,
                endMs = endMs,
                suggestedStartMs = startMs,
                suggestedEndMs = endMs,
                title = r?.title?.ifBlank { null } ?: c.title,
                hashtags = (r?.hashtags?.ifEmpty { null } ?: c.hashtags).joinToString(" "),
                why = r?.why?.ifBlank { null } ?: c.reason,
                firstPassNote = c.reason,
                hook = r?.sub?.hook,
                payoff = r?.sub?.payoff,
                clarity = r?.sub?.clarity,
                emotion = r?.sub?.emotion,
                trendFit = r?.sub?.trendFit,
                loudnessAboveMedianDb = LoudnessMath.maxAboveMedian(db, c.startS, c.endS),
                thumbMs = (samples.minBy { abs(it.timeS - thumbT) }.timeS * 1000).toLong(),
            )
        }
    }
}
