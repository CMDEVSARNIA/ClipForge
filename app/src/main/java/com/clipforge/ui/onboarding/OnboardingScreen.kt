package com.clipforge.ui.onboarding

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipforge.R
import com.clipforge.ui.components.LogoMark
import com.clipforge.ui.home.HomeViewModel
import com.clipforge.ui.settings.SettingsViewModel
import kotlinx.coroutines.delay

/** First-run flow: welcome → connect → how you want to work → first video. */
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    onOpenProject: (Long) -> Unit,
    settingsVm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
    homeVm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val st by settingsVm.state.collectAsStateWithLifecycle()
    val importing by homeVm.importing.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(0) }
    var autopilot by rememberSaveable { mutableStateOf(true) }

    fun finish() {
        settingsVm.finishOnboarding()
        onFinish()
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { step = 3 }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            settingsVm.finishOnboarding()
            homeVm.import(uri)
        }
    }
    LaunchedEffect(Unit) {
        homeVm.events.collect { e -> if (e is HomeViewModel.Event.Open) onOpenProject(e.projectId) }
    }
    // Move on by itself once the connection succeeds.
    LaunchedEffect(st.connected, step) {
        if (step == 1 && st.connected && st.status == "Connected.") { delay(700); step = 2 }
    }

    OnboardingContent(
        step = step,
        connected = st.connected,
        workerUrl = st.workerUrl,
        inviteCode = st.inviteCode,
        busy = st.busy,
        status = st.status,
        statusIsError = st.statusIsError,
        autopilot = autopilot,
        importing = importing,
        onUrl = settingsVm::setUrl,
        onInvite = settingsVm::setInvite,
        onConnect = settingsVm::connect,
        onAutopilot = { autopilot = it },
        onNext = {
            when (step) {
                0 -> step = if (st.connected) 2 else 1
                1 -> if (!st.connected && st.workerUrl.isNotBlank() && st.inviteCode.isNotBlank()) settingsVm.connect() else step = 2
                2 -> {
                    settingsVm.setApAfterImport(autopilot && st.connected)
                    if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else step = 3
                }
                else -> picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
            }
        },
        onSkip = ::finish,
        onBack = { if (step > 0) step-- },
    )
}

/** Stateless layout (also rendered by the screenshot tests). */
@Composable
fun OnboardingContent(
    step: Int,
    connected: Boolean,
    workerUrl: String,
    inviteCode: String,
    busy: Boolean,
    status: String?,
    statusIsError: Boolean,
    autopilot: Boolean,
    importing: Boolean,
    onUrl: (String) -> Unit,
    onInvite: (String) -> Unit,
    onConnect: () -> Unit,
    onAutopilot: (Boolean) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 24.dp)) {
            // Progress dots + skip
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) TextButton(onClick = onBack) { Text("Back") } else Spacer(Modifier.width(64.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                    repeat(4) { i ->
                        Box(
                            Modifier.padding(horizontal = 4.dp).size(width = if (i == step) 22.dp else 8.dp, height = 8.dp)
                                .background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                        )
                    }
                }
                TextButton(onClick = onSkip) { Text(if (step == 3) "Later" else "Skip") }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "onboarding",
            ) { s ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (s) {
                        0 -> Welcome()
                        1 -> Connect(connected, workerUrl, inviteCode, busy, status, statusIsError, onUrl, onInvite, onConnect)
                        2 -> WorkStyle(autopilot, connected, onAutopilot)
                        else -> FirstVideo(importing)
                    }
                }
            }
            Button(
                onClick = onNext,
                enabled = !importing && !(step == 1 && busy),
                shape = RoundedCornerShape(50),
                contentPadding = PaddingValues(vertical = 16.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
            ) {
                Text(
                    when (step) {
                        0 -> "Get started"
                        1 -> when {
                            connected -> "Continue"
                            workerUrl.isNotBlank() && inviteCode.isNotBlank() -> "Connect"
                            else -> "Set up later"
                        }
                        2 -> "Continue"
                        else -> "Import a video"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun Headline(title: String, body: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(10.dp))
    Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun Welcome() {
    Box(Modifier.size(112.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) {
        LogoMark(64.dp)
    }
    Spacer(Modifier.height(28.dp))
    Headline("Long gameplay in.\nShorts out.", "Import a recording and ClipForge does the editing for you.")
    Spacer(Modifier.height(28.dp))
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Feature(R.drawable.ic_sparkle, "Finds the moments worth posting", "Ranked best to worst, against what's trending for your game.")
        Feature(R.drawable.ic_movie, "Frames them vertically", "9:16 crops, facecam layouts, ready for TikTok and Shorts.")
        Feature(R.drawable.ic_check, "Captions and titles them", "Word-by-word captions, titles and hashtags.")
    }
}

@Composable
private fun Feature(icon: Int, title: String, body: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Connect(
    connected: Boolean, url: String, invite: String, busy: Boolean, status: String?, isError: Boolean,
    onUrl: (String) -> Unit, onInvite: (String) -> Unit, onConnect: () -> Unit,
) {
    Headline(
        "Connect your server",
        "ClipForge reaches Claude through your own private server, so your API key never lives on this phone. You only do this once.",
    )
    Spacer(Modifier.height(24.dp))
    if (connected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_check), null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(8.dp))
            Text("Connected", style = MaterialTheme.typography.titleMedium)
        }
        return
    }
    OutlinedTextField(
        value = url, onValueChange = onUrl, label = { Text("Server URL") },
        placeholder = { Text("https://….workers.dev") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = invite, onValueChange = onInvite, label = { Text("Invite code") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    if (busy) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Connecting…", style = MaterialTheme.typography.bodySmall)
        }
    }
    status?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(16.dp))
    Text(
        "No server yet? Ask whoever shared ClipForge with you for the URL and invite code, or follow \"Cloudflare Worker\" in the README to set up your own. You can skip this and do it later in Settings.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun WorkStyle(autopilot: Boolean, connected: Boolean, onChange: (Boolean) -> Unit) {
    Headline("How do you want to work?", "You can change this anytime in Settings.")
    Spacer(Modifier.height(24.dp))
    Choice(
        selected = autopilot, title = "Autopilot", badge = "Recommended",
        body = "Import a video and get finished clips, with a notification when they're ready. Uses Claude credit on every import." +
            if (!connected) " Starts working once you connect a server." else "",
    ) { onChange(true) }
    Spacer(Modifier.height(12.dp))
    Choice(selected = !autopilot, title = "Hands-on", badge = null, body = "Review the ranked moments and choose what to export yourself.") { onChange(false) }
}

@Composable
private fun Choice(selected: Boolean, title: String, badge: String?, body: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().border(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            shape = RoundedCornerShape(20.dp),
        ),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                badge?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FirstVideo(importing: Boolean) {
    Box(Modifier.size(112.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(32.dp)), contentAlignment = Alignment.Center) {
        if (importing) CircularProgressIndicator(Modifier.size(36.dp))
        else Icon(painterResource(R.drawable.ic_add), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(44.dp))
    }
    Spacer(Modifier.height(28.dp))
    Headline(
        if (importing) "Reading your video…" else "Import your first video",
        "A 5–10 minute recording makes a quick first run. Longer recordings, up to an hour, work too.",
    )
}
