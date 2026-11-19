package com.clipforge.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.clipforge.R
import com.clipforge.assistant.ChatItem
import com.clipforge.ui.components.LogoMark
import com.clipforge.ui.project.usd

val SUGGESTIONS = listOf(
    "Make clip 2 shorter",
    "Punchier titles for the top 3",
    "Use Split layout on every clip",
    "Cut the first second of clip 1",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(onBack: () -> Unit, onOpenSettings: () -> Unit, vm: AssistantViewModel = viewModel(factory = AssistantViewModel.Factory)) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    AssistantContent(ui, onBack, onOpenSettings, vm::send, vm::undo, vm::newChat)
}

/** Stateless chat layout (also rendered by the screenshot tests). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantContent(
    ui: AssistantViewModel.UiState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSend: (String) -> Unit,
    onUndo: () -> Unit,
    onNewChat: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(ui.items.size, ui.sending) { if (ui.items.isNotEmpty()) list.animateScrollToItem(ui.items.size) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Assistant", style = MaterialTheme.typography.titleLarge)
                        Text(ui.projectName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") } },
                actions = {
                    IconButton(onClick = onUndo, enabled = ui.canUndo && !ui.sending) { Icon(painterResource(R.drawable.ic_undo), "Undo last changes") }
                    TextButton(onClick = onNewChat, enabled = !ui.sending && ui.items.isNotEmpty()) { Text("New chat") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (ui.items.isEmpty()) item { Intro(ui.connected, onOpenSettings) { onSend(it) } }
                items(ui.items) { ChatRow(it) }
                if (ui.sending) item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Working on it…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ui.error?.let { e -> item { Text(e, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) } }
            }
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("Ask for an edit…") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                            enabled = ui.connected,
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = { onSend(input); input = "" },
                            enabled = input.isNotBlank() && !ui.sending && ui.connected,
                            modifier = Modifier.size(48.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) { Icon(painterResource(R.drawable.ic_send), "Send", Modifier.size(20.dp)) }
                    }
                    Text(
                        "Changes apply right away. Undo reverts the last request. ${ui.model.removePrefix("claude-")} · ${usd(ui.costUsd)} this chat",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Intro(connected: Boolean, onOpenSettings: () -> Unit, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            LogoMark(36.dp)
        }
        Spacer(Modifier.size(16.dp))
        Text("Edit this project by asking", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.size(6.dp))
        Text(
            "Trim clips, change framing and layouts, rewrite titles and hashtags. Refer to clips by their number.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.size(16.dp))
        if (!connected) {
            TextButton(onClick = onOpenSettings) { Text("Connect to your Worker in Settings first") }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SUGGESTIONS.forEach { s -> SuggestionChip(onClick = { onPick(s) }, label = { Text(s) }) }
            }
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) { Text(item.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
        }
        is ChatItem.Assistant -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) { Text(item.text.replace("**", ""), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
        is ChatItem.Action -> Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = 6.dp)) {
            Icon(
                painterResource(if (item.isError) R.drawable.ic_close else R.drawable.ic_check),
                null,
                tint = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp).padding(top = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (item.tool == "get_clip_details") "Read clip details" else item.summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
