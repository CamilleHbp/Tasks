package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import studio.camille.vervetwodo.integration.*

@Composable
fun SyncSettings(status: SyncReply, busy: Boolean, text: (String) -> String, close: () -> Unit,
    connect: (String, String) -> Unit, sync: () -> Unit) {
    var origin by remember { mutableStateOf(status.state.origin.ifEmpty { "https://vervetwodo.195-15-214-30.sslip.io" }) }
    var code by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(text("syncTitle")) }, text = {
        Column(Modifier.widthIn(min = 320.dp, max = 560.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text("syncDescription"))
            Text(text(if (status.state.authenticated) "syncConnected" else "syncDisconnected"))
            if (status.state.authenticated) Text(if (status.state.pending < 0) text("syncNative") else "${text("syncPending")}: ${status.state.pending}")
            if (status.error != null) Text(text("syncError"), color = MaterialTheme.colorScheme.error)
            if (busy || status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedTextField(origin, { origin = it }, singleLine = true, label = { Text(text("syncServer")) }, enabled = !status.state.authenticated && !busy)
            OutlinedTextField(code, { code = it }, singleLine = true, label = { Text(text("syncCode")) }, visualTransformation = PasswordVisualTransformation(), enabled = !busy)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = code.isNotBlank() && !busy, onClick = { connect(origin, code); code = "" }) { Text(text("syncConnect")) }
                if (status.state.authenticated) OutlinedButton(onClick = sync, enabled = !busy) { Text(text("syncNow")) }
            }

        }
    }, confirmButton = { TextButton(onClick = close) { Text(text("done")) } })
}
