package com.clipforge

import android.app.Application
import androidx.room.Room
import com.clipforge.analysis.AnalysisWorker
import com.clipforge.analysis.MomentAnalyzer
import com.clipforge.analysis.SignalsStore
import com.clipforge.data.ProjectRepository
import com.clipforge.data.db.AppDatabase
import com.clipforge.media.ThumbnailLoader
import com.clipforge.media.VideoProbe
import com.clipforge.net.WorkerClient
import com.clipforge.settings.SettingsRepository

class ClipForgeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AnalysisWorker.createChannel(this)
    }
}

/** Tiny manual DI container; avoids pulling in a DI framework for a single-module app. */
class AppContainer(app: Application) {
    val database: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "clipforge.db").build()
    val settings = SettingsRepository(app)
    val worker = WorkerClient(settings)
    val videoProbe = VideoProbe(app)
    val thumbnails = ThumbnailLoader(app)
    val signals = SignalsStore(app)
    val projects = ProjectRepository(app, database.projectDao(), videoProbe, signals)
    val analyzer = MomentAnalyzer(
        app, database.projectDao(), database.analysisDao(), database.clipDao(), worker, settings, signals,
    )
}
