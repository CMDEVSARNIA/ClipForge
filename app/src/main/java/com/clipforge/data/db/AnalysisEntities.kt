package com.clipforge.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "analyses",
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId")],
)
data class AnalysisEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    /** RUNNING, DONE, FAILED, CANCELLED */
    val status: String,
    val game: String?,
    val gameDetected: Boolean,
    /** YOUTUBE or BUILT_IN */
    val trendSource: String?,
    val trendNote: String?,
    val trendSummary: String?,
    val analysisModel: String,
    val rankModel: String,
    val estimatedCostUsd: Double,
    val actualCostUsd: Double = 0.0,
    val framesSampled: Int = 0,
    val batchesFailed: Int = 0,
    val warning: String? = null,
    val error: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

@Entity(
    tableName = "clips",
    foreignKeys = [ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("projectId"), Index("analysisId")],
)
data class ClipEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val analysisId: Long,
    /** 1 = best. */
    val rank: Int,
    val tier: String,
    val score: Int,
    val startMs: Long,
    val endMs: Long,
    /** Edges as suggested (after scene snapping), for "Reset". */
    val suggestedStartMs: Long,
    val suggestedEndMs: Long,
    val title: String,
    /** Space-separated, each starting with '#'. */
    val hashtags: String,
    val why: String,
    val firstPassNote: String,
    val hook: Int?,
    val payoff: Int?,
    val clarity: Int?,
    val emotion: Int?,
    val trendFit: Int?,
    val loudnessAboveMedianDb: Float,
    val thumbMs: Long,
    val edited: Boolean = false,
    /** AUTO (text cards / wide shots → fit+blur, else center) or a fixed [com.clipforge.export.CropMode]. */
    @ColumnInfo(defaultValue = "AUTO") val cropMode: String = "AUTO",
    @ColumnInfo(defaultValue = "0") val panX: Float = 0f,
    /** Zoom-fit source rect as "l,t,r,b" (normalized). */
    val zoomRect: String? = null,
    /** Explicit per-segment layout (JSON list of Segment) when the user split the clip; null = derived. */
    val segmentsJson: String? = null,
    val exportedUri: String? = null,
    val exportedAt: Long? = null,
) {
    val lengthMs: Long get() = endMs - startMs
}

@Dao
interface AnalysisDao {
    @Insert
    suspend fun insert(a: AnalysisEntity): Long

    @Update
    suspend fun update(a: AnalysisEntity)

    @Query("SELECT * FROM analyses WHERE id = :id")
    suspend fun get(id: Long): AnalysisEntity?

    @Query("SELECT * FROM analyses WHERE projectId = :projectId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(projectId: Long): Flow<AnalysisEntity?>

    @Query("UPDATE analyses SET status = 'FAILED', error = 'Interrupted', finishedAt = :now WHERE status = 'RUNNING' AND projectId = :projectId")
    suspend fun markStaleRunsFailed(projectId: Long, now: Long)
}

@Dao
interface ClipDao {
    @Query("SELECT * FROM clips WHERE projectId = :projectId ORDER BY rank ASC")
    fun observeForProject(projectId: Long): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clips WHERE id = :id")
    fun observe(id: Long): Flow<ClipEntity?>

    @Query("SELECT * FROM clips WHERE id = :id")
    suspend fun get(id: Long): ClipEntity?

    @Insert
    suspend fun insertAll(clips: List<ClipEntity>)

    @Update
    suspend fun update(clip: ClipEntity)

    @Query("SELECT * FROM clips WHERE id IN (:ids) ORDER BY rank ASC")
    suspend fun getAll(ids: List<Long>): List<ClipEntity>

    @Query("UPDATE clips SET exportedUri = :uri, exportedAt = :at WHERE id = :id")
    suspend fun markExported(id: Long, uri: String, at: Long)

    @Query("DELETE FROM clips WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: Long)

    @Transaction
    suspend fun replaceForProject(projectId: Long, clips: List<ClipEntity>) {
        deleteForProject(projectId)
        insertAll(clips)
    }
}
