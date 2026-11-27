package cn.super12138.todo.ui.pages.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.super12138.todo.R
import cn.super12138.todo.logic.sync.SyncRepository
import cn.super12138.todo.ui.pages.settings.components.SettingsItem
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun SettingsSync(repository: SyncRepository = koinInject()) {
    val status by repository.status.collectAsState()
    var open by remember { mutableStateOf(false) }
    SettingsItem(leadingIconRes = R.drawable.ic_dns, title = stringResource(R.string.sync_title),
        description = stringResource(if (status.state.authenticated) R.string.sync_connected else R.string.sync_disconnected), onClick = { open = true })
    if (open) SyncConnectionDialog(repository) { open = false }
}

@Composable
fun SyncConnectionDialog(repository: SyncRepository, close: () -> Unit) {
    val status by repository.status.collectAsState()
    val scope = rememberCoroutineScope()
    var origin by remember(status.state.origin) { mutableStateOf(status.state.origin.ifEmpty { "https://tasks.camille.studio" }) }
    var code by remember { mutableStateOf("") }
    var editingCode by remember { mutableStateOf(false) }
    val hasSavedAccess = status.state.authenticated && origin == status.state.origin
    var feedback by remember { mutableStateOf<Int?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.sync_title)) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sync_description))
            if (status.state.authenticated) Text(if (status.state.pending < 0) stringResource(R.string.native_sync_status) else stringResource(R.string.sync_pending_count, status.state.pending))
            feedback?.let { Text(stringResource(it), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            if (feedback == null && status.error != null) Text(stringResource(R.string.sync_error), color = MaterialTheme.colorScheme.error)
            if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(origin, { origin = it; code = ""; feedback = null }, label = { Text(stringResource(R.string.sync_server)) }, singleLine = true, enabled = !status.busy)
            OutlinedTextField(if (hasSavedAccess && code.isEmpty() && !editingCode) "********" else code, { code = it; feedback = null }, modifier = Modifier.onFocusChanged { editingCode = it.isFocused }, label = { Text(stringResource(R.string.sync_code)) }, singleLine = true, enabled = !status.busy, visualTransformation = PasswordVisualTransformation())
            Button(enabled = code.isNotBlank() && !status.busy, onClick = {
                val credential = code; code = ""; feedback = null; scope.launch { feedback = if (repository.connect(origin.trim(), credential)) R.string.sync_connected else when (repository.status.value.error) { "invalid_code", "sign_in_required" -> R.string.sync_invalid_code; "identity_mismatch" -> R.string.sync_other_workspace; else -> R.string.sync_error } }
            }) { Text(stringResource(R.string.sync_connect)) }
            if (status.state.authenticated) OutlinedButton(enabled = !status.busy, onClick = { scope.launch { repository.synchronize() } }) { Text(stringResource(R.string.sync_now)) }

        }
    }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.sync_close)) } })
}
