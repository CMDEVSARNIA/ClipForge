package com.clipforge.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.clipforge.media.VideoInfo

@Entity(tableName = "projects", indices = [Index(value = ["sourceUri"], unique = true)])
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUri: String,
    val displayName: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val frameRate: Float,
    val bitrate: Int?,
    val videoMime: String?,
    val hasAudio: Boolean,
    val sizeBytes: Long?,
    /** False when the picker didn't grant a persistable permission; the video may vanish after a reboot. */
    val persistedPermission: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun toVideoInfo() = VideoInfo(
        durationMs = durationMs,
        width = width,
        height = height,
        rotationDegrees = rotationDegrees,
        frameRate = frameRate,
        bitrate = bitrate,
        videoMime = videoMime,
        hasAudio = hasAudio,
        sizeBytes = sizeBytes,
        displayName = displayName,
    )
}
