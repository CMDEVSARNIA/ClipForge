package com.clipforge

import android.app.Application
import androidx.room.Room
import com.clipforge.data.ProjectRepository
import com.clipforge.data.db.AppDatabase
import com.clipforge.media.ThumbnailLoader
import com.clipforge.media.VideoProbe

class ClipForgeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Tiny manual DI container; avoids pulling in a DI framework for a single-module app. */
class AppContainer(app: Application) {
    val database: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "clipforge.db").build()
    val videoProbe = VideoProbe(app)
    val thumbnails = ThumbnailLoader(app)
    val projects = ProjectRepository(app, database.projectDao(), videoProbe)
}
