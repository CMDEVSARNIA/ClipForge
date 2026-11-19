package com.clipforge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE sourceUri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): ProjectEntity?

    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Query("UPDATE projects SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE projects SET camPresetId = :presetId WHERE id = :id")
    suspend fun setCamPreset(id: Long, presetId: Long?)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)
}
