package com.clipforge.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, AnalysisEntity::class, ClipEntity::class, CamPresetEntity::class],
    version = 5,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun clipDao(): ClipDao
    abstract fun camPresetDao(): CamPresetDao
}
