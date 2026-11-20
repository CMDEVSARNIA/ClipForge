package com.clipforge.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.clipforge.ui.assistant.AssistantScreen
import com.clipforge.ui.cam.CamSetupScreen
import com.clipforge.ui.clip.ClipEditorScreen
import com.clipforge.ui.home.HomeScreen
import com.clipforge.ui.onboarding.OnboardingScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import com.clipforge.ui.project.ProjectScreen
import com.clipforge.ui.settings.SettingsScreen
import kotlinx.serialization.Serializable

@Serializable
object OnboardingRoute

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

@Serializable
data class AssistantRoute(val projectId: Long)

@Composable
fun ClipForgeNavHost() {
    // Decide the first screen once settings load: onboarding for new installs, home otherwise.
    val context = androidx.compose.ui.platform.LocalContext.current
    val settingsRepo = (context.applicationContext as com.clipforge.ClipForgeApp).container.settings
    val start by androidx.compose.runtime.produceState<Any?>(null) {
        val s = settingsRepo.current()
        // Installs that were already connected before onboarding existed skip it.
        if (!s.onboardingDone && s.isConnected) settingsRepo.setOnboardingDone()
        value = if (s.onboardingDone || s.isConnected) HomeRoute else OnboardingRoute
    }
    val first = start ?: run {
        androidx.compose.foundation.layout.Box(
            androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background)
        )
        return
    }
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = first) {
        composable<OnboardingRoute> {
            OnboardingScreen(
                onFinish = { nav.navigate(HomeRoute) { popUpTo<OnboardingRoute> { inclusive = true } } },
                onOpenProject = { id ->
                    nav.navigate(HomeRoute) { popUpTo<OnboardingRoute> { inclusive = true } }
                    nav.navigate(ProjectRoute(id))
                },
            )
        }
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
                onOpenAssistant = { id -> nav.navigate(AssistantRoute(id)) },
            )
        }
        composable<AssistantRoute> {
            AssistantScreen(onBack = { nav.popBackStack() }, onOpenSettings = { nav.navigate(SettingsRoute) })
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
