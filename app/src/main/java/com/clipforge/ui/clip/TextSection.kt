package com.clipforge.ui.clip

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipforge.export.TitleStyle
import com.clipforge.export.CaptionStyle

@Composable
fun TextSection(st: ClipEditorViewModel.State, captionRun: String?, vm: ClipEditorViewModel) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Title overlay", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = st.showTitle, onCheckedChange = vm::setShowTitle)
            }
            OutlinedTextField(value = st.title, onValueChange = vm::setTitle, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            if (st.titleOptions.isNotEmpty()) {
                Text("Claude's options", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    st.titleOptions.forEach { t ->
                        SuggestionChip(onClick = { vm.setTitle(t) }, label = { Text(t, maxLines = 2, overflow = TextOverflow.Ellipsis) })
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TitleStyle.entries.forEach { s -> FilterChip(selected = st.titleStyle == s, onClick = { vm.setTitleStyle(s) }, label = { Text(s.label) }) }
            }
            if (st.hashtags.isNotBlank()) Text(st.hashtags, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Speech captions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        captionRun ?: when (st.captionStatus) {
                            "READY" -> "${st.captionWords.size} words · word-by-word highlight"
                            "NO_SPEECH" -> "No speech detected, so captions are skipped for this clip."
                            "NO_AUDIO" -> "This video has no audio track."
                            "FAILED" -> "Last attempt failed. Try again."
                            else -> "Transcribed on your phone (whisper.cpp). The model downloads once."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = st.showCaptions, onCheckedChange = vm::setShowCaptions, enabled = st.captionWords.isNotEmpty())
            }
            if (st.captionWords.isNotEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CaptionStyle.entries.forEach { s -> FilterChip(selected = st.captionStyle == s, onClick = { vm.setCaptionStyle(s) }, label = { Text(s.label) }) }
                }
            }
            OutlinedButton(onClick = vm::generateCaptions, enabled = captionRun == null) {
                Text(if (st.captionStatus == "NONE") "Generate captions & titles" else "Regenerate captions & titles")
            }
            if (st.captionWords.isNotEmpty()) {
                Text(
                    st.captionWords.filter { it.endMs > st.startMs && it.startMs < st.endMs }.joinToString(" ") { it.text },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
