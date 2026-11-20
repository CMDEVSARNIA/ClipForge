package com.clipforge

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.clipforge.data.db.AnalysisEntity
import com.clipforge.data.db.CamPresetEntity
import com.clipforge.data.db.ClipEntity
import com.clipforge.data.db.ProjectEntity
import com.clipforge.media.ThumbnailLoader
import com.clipforge.settings.AppSettings
import com.clipforge.ui.clip.EditorTabs
import com.clipforge.ui.clip.ExportTab
import com.clipforge.ui.home.HomeContent
import com.clipforge.ui.project.AnalyzeCard
import com.clipforge.ui.project.ClipRow
import com.clipforge.ui.project.FacecamCard
import com.clipforge.ui.project.MomentsHeader
import com.clipforge.ui.settings.SettingsScreen
import com.clipforge.ui.theme.ClipForgeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders real screens/components to app/build/screenshots/ for design review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent { ClipForgeTheme { androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) { content() } } }
        rule.waitForIdle()
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun project(id: Long, name: String, min: Int) = ProjectEntity(
        id = id, sourceUri = "content://fake/$id", displayName = name, durationMs = min * 60_000L, width = 1920, height = 1080,
        rotationDegrees = 0, frameRate = 60f, bitrate = 12_000_000, videoMime = "video/avc", hasAudio = true, sizeBytes = 1_500_000_000,
        persistedPermission = true, createdAt = 0, updatedAt = 0,
    )

    private fun clip(id: Long, rank: Int, tier: String, score: Int, title: String, why: String, start: Long) = ClipEntity(
        id = id, projectId = 1, analysisId = 1, rank = rank, tier = tier, score = score, startMs = start, endMs = start + 17_000,
        suggestedStartMs = start, suggestedEndMs = start + 17_000, title = title, hashtags = "#valorant #clutch #ace #fps #gaming",
        why = why, firstPassNote = "", hook = 9, payoff = 9, clarity = 7, emotion = 8, trendFit = 8,
        loudnessAboveMedianDb = 12f, thumbMs = start,
    )

    @Test fun homeEmpty() = shot("01_home_empty") {
        HomeContent(emptyList(), false, null, SnackbarHostState(), {}, {}, {}, {})
    }

    @Test fun homeGrid() = shot("02_home_grid") {
        HomeContent(
            listOf(project(1, "Valorant ranked night.mp4", 58), project(2, "Elden Ring boss attempts.mp4", 41),
                project(3, "GTA V heist with the boys.mp4", 33), project(4, "Fortnite duos.mp4", 22)),
            false, null, SnackbarHostState(), {}, {}, {}, {},
        )
    }

    @Test fun moments() = shot("03_moments") {
        val ctx = LocalContext.current
        val thumbs = ThumbnailLoader(ctx)
        val settings = AppSettings("https://w", "tok", "claude-haiku-5-5", "claude-sonnet-5-5", true, 60, "TINY", "auto")
        val analysis = AnalysisEntity(
            id = 1, projectId = 1, status = "DONE", game = "Valorant", gameDetected = true, trendSource = "YOUTUBE",
            trendNote = "Live: top 25 Shorts from the last 7 days", analysisModel = "", rankModel = "", estimatedCostUsd = 0.2,
            actualCostUsd = 0.184, createdAt = 0,
            trendSummary = "Clutch 1vX rounds with a loud reaction and Operator flicks dominate this week; titles lead with the scoreline.",
        )
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
            AnalyzeCard(settings, null, analysis, 3, {}, {}, {})
            FacecamCard(CamPresetEntity(1, "Stream cam", "0.75,0.65,0.98,0.97", 1.78f, 0), true, {}) { _, _ -> }
            MomentsHeader(3, null, {}, {}, {})
            ClipRow(clip(1, 1, "S", 92, "1v4 clutch with a Sheriff?!", "Huge hook, crowd-pleasing payoff and it matches this week's clutch trend.", 60_000), "content://fake/1", thumbs, {}, {})
            ClipRow(clip(2, 2, "A", 78, "Operator flick through smoke", "Clean flick, but the first second is slow.", 300_000), "content://fake/1", thumbs, {}, {})
            ClipRow(clip(3, 3, "C", 46, "Ninja defuse", "Fun but needs context to land.", 900_000), "content://fake/1", thumbs, {}, {}, selected = true)
        }
    }

    @Test fun editorExportTab() = shot("04_editor_export") {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            EditorTabs(selected = 3, onSelect = {})
            ExportTab(clip(1, 1, "S", 92, "1v4 clutch with a Sheriff?!", "Huge hook, crowd-pleasing payoff and it matches this week's clutch trend.", 60_000), null) {}
        }
    }

    @Test fun settings() = shot("05_settings") { SettingsScreen(onBack = {}) }

    private fun onboarding(step: Int, connected: Boolean = false, autopilot: Boolean = true, url: String = "", code: String = "", name: String = "07_onboarding_$step") = shot(name) {
        com.clipforge.ui.onboarding.OnboardingContent(
            step = step, connected = connected, workerUrl = url, inviteCode = code, busy = false, status = null, statusIsError = false,
            autopilot = autopilot, importing = false, onUrl = {}, onInvite = {}, onConnect = {}, onAutopilot = {},
            onNext = {}, onSkip = {}, onBack = {},
        )
    }

    @Test fun onboardingWelcome() = onboarding(0)
    @Test fun onboardingConnect() = onboarding(1)
    @Test fun onboardingConnectFilled() = onboarding(1, url = "https://clipforge-worker.example.workers.dev", code = "secret", name = "07_onboarding_1_filled")
    @Test fun onboardingStyle() = onboarding(2, connected = true)
    @Test fun onboardingFirstVideo() = onboarding(3, connected = true)

    @Test fun assistant() = shot("06_assistant") {
        com.clipforge.ui.assistant.AssistantContent(
            com.clipforge.ui.assistant.AssistantViewModel.UiState(
                projectName = "Valorant ranked night.mp4",
                items = listOf(
                    com.clipforge.assistant.ChatItem.User("Make clip 2 shorter and give the top 3 punchier titles"),
                    com.clipforge.assistant.ChatItem.Action("trim_clip", "Clip 2: 17.0s → 12.5s (5:01.5–5:14.0).", false),
                    com.clipforge.assistant.ChatItem.Action("get_clip_details", "", false),
                    com.clipforge.assistant.ChatItem.Action("set_title", "Clip 1 title: \"1v4 with a SHERIFF?!\"", false),
                    com.clipforge.assistant.ChatItem.Action("set_title", "Clip 2 title: \"Through-smoke Op flick\"", false),
                    com.clipforge.assistant.ChatItem.Action("set_title", "Clip 3 title: \"Ninja defuse, 0.2s left\"", false),
                    com.clipforge.assistant.ChatItem.Assistant("Done. Clip 2 now ends right after the flick (12.5 s), and all three titles lead with the moment."),
                    com.clipforge.assistant.ChatItem.User("Split layout on clip 3"),
                    com.clipforge.assistant.ChatItem.Action("set_layout", "No facecam is set up for this project yet (project screen → Facecam → Set up).", true),
                    com.clipforge.assistant.ChatItem.Assistant("There's no facecam on this project yet. Set one up from the project screen (Facecam → Set up), then ask again."),
                ),
                canUndo = true, costUsd = 0.0213, model = "claude-sonnet-5-5",
            ),
            {}, {}, {}, {}, {},
        )
    }
}
