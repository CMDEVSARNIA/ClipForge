package com.clipforge.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import com.clipforge.data.db.ProjectDao
import com.clipforge.data.db.ProjectEntity
import com.clipforge.media.VideoProbe
import kotlinx.coroutines.flow.Flow

class ProjectRepository(
    private val context: Context,
    private val dao: ProjectDao,
    private val probe: VideoProbe,
) {
    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeAll()

    fun observeProject(id: Long): Flow<ProjectEntity?> = dao.observe(id)

    /**
     * Imports a picked video: keeps read access across restarts (when the picker allows it),
     * probes metadata and stores a project. Re-importing the same URI returns the existing project.
     */
    suspend fun importVideo(uri: Uri): ImportResult {
        dao.findByUri(uri.toString())?.let { existing ->
            dao.touch(existing.id, System.currentTimeMillis())
            return ImportResult(existing.id, existing.toVideoInfo(), alreadyImported = true)
        }

        val persisted = try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        } catch (_: SecurityException) {
            false
        }

        val info = probe.probe(uri)
        val now = System.currentTimeMillis()
        val id = dao.insert(
            ProjectEntity(
                sourceUri = uri.toString(),
                displayName = info.displayName ?: "Video ${java.text.DateFormat.getDateTimeInstance().format(now)}",
                durationMs = info.durationMs,
                width = info.width,
                height = info.height,
                rotationDegrees = info.rotationDegrees,
                frameRate = info.frameRate,
                bitrate = info.bitrate,
                videoMime = info.videoMime,
                hasAudio = info.hasAudio,
                sizeBytes = info.sizeBytes,
                persistedPermission = persisted,
                createdAt = now,
                updatedAt = now,
            )
        )
        return ImportResult(id, info, alreadyImported = false)
    }

    suspend fun touch(id: Long) = dao.touch(id, System.currentTimeMillis())

    suspend fun delete(project: ProjectEntity) {
        dao.delete(project.id)
        if (project.persistedPermission) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    project.sourceUri.toUri(), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    data class ImportResult(val projectId: Long, val info: com.clipforge.media.VideoInfo, val alreadyImported: Boolean)
}
