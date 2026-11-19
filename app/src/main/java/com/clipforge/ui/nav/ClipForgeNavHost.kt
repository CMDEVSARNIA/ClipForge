package com.clipforge.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.clipforge.ui.home.HomeScreen
import com.clipforge.ui.project.ProjectScreen
import kotlinx.serialization.Serializable

@Serializable
object HomeRoute

@Serializable
data class ProjectRoute(val projectId: Long)

@Composable
fun ClipForgeNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = HomeRoute) {
        composable<HomeRoute> {
            HomeScreen(onOpenProject = { id -> nav.navigate(ProjectRoute(id)) })
        }
        composable<ProjectRoute> {
            ProjectScreen(onBack = { nav.popBackStack() })
        }
    }
}
