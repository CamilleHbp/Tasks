package cn.super12138.todo.ui.pages.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    var origin by remember(status.state.origin) { mutableStateOf(status.state.origin.ifEmpty { "https://vervetwodo.195-15-214-30.sslip.io" }) }
    var code by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.sync_title)) }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.sync_description))
            if (status.state.authenticated) Text(if (status.state.pending < 0) stringResource(R.string.native_sync_status) else stringResource(R.string.sync_pending_count, status.state.pending))
            if (status.error != null) Text(stringResource(R.string.sync_error), color = MaterialTheme.colorScheme.error)
            if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(origin, { origin = it }, label = { Text(stringResource(R.string.sync_server)) }, singleLine = true, enabled = !status.state.authenticated && !status.busy)
            OutlinedTextField(code, { code = it }, label = { Text(stringResource(R.string.sync_code)) }, singleLine = true, enabled = !status.busy, visualTransformation = PasswordVisualTransformation())
            Button(enabled = code.isNotBlank() && !status.busy, onClick = {
                val credential = code; code = ""; scope.launch { repository.connect(origin, credential) }
            }) { Text(stringResource(R.string.sync_connect)) }
            if (status.state.authenticated) OutlinedButton(enabled = !status.busy, onClick = { scope.launch { repository.synchronize() } }) { Text(stringResource(R.string.sync_now)) }

        }
    }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.sync_close)) } })
}
