package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import studio.camille.vervetwodo.integration.*

/** App-level attention, independent of the current page or settings visibility. */
@Composable
fun SyncAttention(status: SyncReply, busy: Boolean, text: (String) -> String,
    retry: () -> Unit, settings: () -> Unit, resolve: (SyncRecord, String, String) -> Unit, showBanner: Boolean = true, showConflicts: Boolean = true) {
    val conflicts = status.state.conflicts()
    val working = busy || status.busy
    if (showBanner && (status.error != null || status.state.pending > 0)) {
        val failed = status.error != null
        Surface(color = if (failed) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text(if (failed) "syncFailureTitle" else "syncWaiting"), style = MaterialTheme.typography.titleSmall)
                Text(text(if (failed) "syncFailureHint" else "syncPendingHint"), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = retry, enabled = !working) { Text(text("syncRetry")) }
                    TextButton(onClick = settings) { Text(text("syncOpenSettings")) }
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
    if (showConflicts) conflicts.firstOrNull()?.let { conflict ->
        // A new remote version invalidates a selection, even if the record/field is unchanged.
        key(conflict.record.id, conflict.field, conflict.versions) {
            var selected by remember { mutableStateOf<String?>(null) }
            Dialog(onDismissRequest = {}, properties = DialogProperties(
                dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
                Surface(Modifier.padding(16.dp).widthIn(max = 680.dp).fillMaxWidth().heightIn(max = 680.dp),
                    shape = MaterialTheme.shapes.extraLarge) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(text("syncAttentionTitle"), style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.semantics { heading() })
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(text("syncAttentionDescription"), style = MaterialTheme.typography.bodyMedium)
                            Text(text("syncRemaining").format(conflicts.size), style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            HorizontalDivider()
                            Text(conflict.record.value(if (conflict.record.kind == "task") "title" else "name")
                                .ifBlank { text("syncUnnamed") }, style = MaterialTheme.typography.titleLarge)
                            Text(text("syncField_" + conflict.field), style = MaterialTheme.typography.titleMedium)
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                conflict.versions.distinctBy { it.value }.forEach { version ->
                                    val chosen = selected == version.value
                                    Surface(shape = MaterialTheme.shapes.medium,
                                        color = if (chosen) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                                        Row(Modifier.fillMaxWidth().selectable(selected = chosen, enabled = !working,
                                            role = Role.RadioButton, onClick = { selected = version.value }).padding(12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                                            RadioButton(selected = chosen, onClick = null, enabled = !working)
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(status.state.conflictValue(conflict.field, version.value, text), style = MaterialTheme.typography.bodyLarge)
                                                val sources = conflict.versions.filter { it.value == version.value }
                                                    .map { it.source?.name?.takeIf(String::isNotBlank) ?: text("syncUnknownDevice") }.distinct()
                                                Text(text("syncSource").format(sources.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                            if (status.error != null) Text(text(if (status.error == "task_changed") "syncChanged" else "syncFailureHint"),
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        }
                        if (status.error != null) TextButton(onClick = settings) { Text(text("syncOpenSettings")) }
                        if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button(onClick = { selected?.let { resolve(conflict.record, conflict.field, it) } },
                            enabled = selected != null && !working, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(text(if (selected == null) "syncChoose" else "syncKeep"))
                        }
                    }
                }
            }
        }
    }
}
