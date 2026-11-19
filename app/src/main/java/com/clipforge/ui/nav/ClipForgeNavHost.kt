package com.clipforge.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.clipforge.ui.cam.CamSetupScreen
import com.clipforge.ui.clip.ClipEditorScreen
import com.clipforge.ui.home.HomeScreen
import com.clipforge.ui.project.ProjectScreen
import com.clipforge.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
data class ProjectRoute(val projectId: Long)

@Serializable
data class ClipEditorRoute(val clipId: Long)

@Serializable
object SettingsRoute

@Serializable
data class CamSetupRoute(val projectId: Long, val atMs: Long)

@Composable
fun ClipForgeNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(
                onOpenProject = { id -> nav.navigate(ProjectRoute(id)) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
            )
        }
        composable<ProjectRoute> {
            ProjectScreen(
                onBack = { nav.popBackStack() },
                onOpenClip = { id -> nav.navigate(ClipEditorRoute(id)) },
                onOpenSettings = { nav.navigate(SettingsRoute) },
                onOpenCamSetup = { id, at -> nav.navigate(CamSetupRoute(id, at)) },
            )
        }
        composable<CamSetupRoute> {
            CamSetupScreen(onBack = { nav.popBackStack() })
        }
        composable<ClipEditorRoute> {
            ClipEditorScreen(onBack = { nav.popBackStack() })
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
