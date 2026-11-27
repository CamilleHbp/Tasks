package cn.super12138.todo.ui.components

import kotlinx.coroutines.launch
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import studio.camille.vervetwodo.integration.*
import java.text.DateFormat
import java.util.Date

@Composable
private fun syncStatusColor(health: SyncHealth): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when (health) {
        SyncHealth.SYNCED -> if (dark) Color(0xFF8ED9A0) else Color(0xFF1B6E39)
        SyncHealth.CONFLICT, SyncHealth.OFFLINE, SyncHealth.PENDING, SyncHealth.UNCONFIRMED, SyncHealth.STALE ->
            if (dark) Color(0xFFFFBE69) else Color(0xFF895000)
        SyncHealth.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** The native button owns accessibility; these shapes never rely on color alone. */
@Composable
fun SyncStatusIcon(health: SyncHealth, active: Boolean) {
    val color = syncStatusColor(health)
    var animate by remember { mutableStateOf(false) }
    LaunchedEffect(active) { animate = false; if (active) { delay(300); animate = true } }
    // Framework animation follows the platform motion-duration scale.
    val angle = if (animate) {
        val transition = rememberInfiniteTransition(label = "sync")
        val rotation by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sync rotation")
        rotation
    } else 0f
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(22.dp)) {
            val w = size.width; val stroke = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round)
            fun line(ax: Float, ay: Float, bx: Float, by: Float) = drawLine(color,
                Offset(w*ax,w*ay), Offset(w*bx,w*by), stroke.width, StrokeCap.Round)
            when (health) {
                SyncHealth.SYNCED -> {
                    drawCircle(color,w*.43f,style=stroke)
                    line(.27f,.5f,.43f,.65f); line(.43f,.65f,.74f,.33f)
                }
                SyncHealth.ERROR -> {
                    drawCircle(color,w*.43f,style=stroke)
                    line(.5f,.25f,.5f,.53f); drawCircle(color,w*.045f,Offset(w*.5f,w*.72f))
                }
                SyncHealth.CONFLICT, SyncHealth.OFFLINE, SyncHealth.PENDING, SyncHealth.UNCONFIRMED, SyncHealth.STALE -> {
                    val path = Path().apply { moveTo(w*.5f,w*.06f); lineTo(w*.96f,w*.88f); lineTo(w*.04f,w*.88f); close() }
                    drawPath(path,color,style=stroke)
                    line(.5f,.34f,.5f,.56f); drawCircle(color,w*.04f,Offset(w*.5f,w*.73f))
                }
                SyncHealth.DISCONNECTED -> { drawCircle(color,w*.43f,style=stroke); line(.2f,.8f,.8f,.2f) }
                else -> rotate(angle) {
                    drawArc(color,205f,125f,false,Offset(w*.13f,w*.13f),Size(w*.74f,w*.74f),style=stroke)
                    line(.82f,.13f,.86f,.35f); line(.86f,.35f,.64f,.34f)
                    drawArc(color,25f,125f,false,Offset(w*.13f,w*.13f),Size(w*.74f,w*.74f),style=stroke)
                    line(.18f,.87f,.14f,.65f); line(.14f,.65f,.36f,.66f)
                }
            }
        }
        if (animate && health !in setOf(SyncHealth.SYNCING,SyncHealth.CHECKING)) {
            Canvas(Modifier.size(28.dp)) { rotate(angle) { drawArc(color,0f,90f,false,style=Stroke(1.dp.toPx())) } }
        }
    }
}

@Composable
fun SyncStatusDetails(status: SyncReply, now: Long, text: (String) -> String,
    retry: () -> Unit, settings: () -> Unit, resolve: () -> Unit, close: () -> Unit) {
    val health = status.health(now)
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    fun timestamp(value: Long) = if (value <= 0) text("syncStatusNever") else DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM,locale).format(Date(value))
    Column(Modifier.fillMaxWidth().heightIn(max=560.dp).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            SyncStatusIcon(health,status.busy)
            Text(text(health.labelKey()),style=MaterialTheme.typography.titleLarge,color=syncStatusColor(health),
                modifier=Modifier.weight(1f).semantics { heading(); liveRegion=LiveRegionMode.Polite })
        }
        Text(text(health.detailKey()),style=MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(text("syncStatusLastSuccess"),style=MaterialTheme.typography.labelMedium)
            Text(timestamp(status.telemetry.lastSuccessAt),style=MaterialTheme.typography.bodyMedium)
        }
        if (status.telemetry.lastContactAt != status.telemetry.lastSuccessAt) Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Text(text("syncStatusLastCheck"),style=MaterialTheme.typography.labelMedium)
            Text(timestamp(status.telemetry.lastContactAt),style=MaterialTheme.typography.bodyMedium)
        }
        Text(if (status.state.pending<0 || status.localChanges) text("syncStatusPendingUnknown") else text("syncStatusPendingCount").format(status.state.pending))
        Text(text("syncStatusConflicts").format(status.state.conflicts().size))
        Button(enabled=!status.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp),onClick={
            if (status.error == "sign_in_required") { close(); settings() } else when(health) {
                SyncHealth.CONFLICT -> { close(); resolve() }
                SyncHealth.DISCONNECTED -> { close(); settings() }
                else -> retry()
            }
        }) { Text(text(if (status.error == "sign_in_required") "syncStatusConnect" else when(health) {
            SyncHealth.CONFLICT -> "syncStatusResolve"
            SyncHealth.DISCONNECTED -> "syncStatusConnect"
            SyncHealth.ERROR,SyncHealth.OFFLINE -> "syncStatusRetry"
            else -> "syncStatusCheck"
        })) }
        TextButton(onClick={ close(); settings() },modifier=Modifier.fillMaxWidth()) { Text(text("syncStatusSettings")) }
        TextButton(onClick=close,modifier=Modifier.fillMaxWidth()) { Text(text("syncStatusClose")) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusButton(status: SyncReply, text: (String) -> String, retry: () -> Unit, settings: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now=System.currentTimeMillis(); delay(1000) } }
    val currentNow=remember(now,status) { System.currentTimeMillis() }
    val health=status.health(currentNow)
    IconButton(onClick={ open=true },modifier=Modifier.size(48.dp).semantics {
        contentDescription=text("syncStatusAction").format(text(health.labelKey()))
        stateDescription=text(health.labelKey())
    }) { SyncStatusIcon(health,status.busy) }
    if(open) ModalBottomSheet(onDismissRequest={ open=false },sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        SyncStatusDetails(status,currentNow,text,retry,settings,{ open=false },{ open=false })
    }
}

@Composable
fun AppSyncStatusButton(repository: cn.super12138.todo.logic.sync.SyncRepository = org.koin.compose.koinInject()) {
    val status by repository.status.collectAsState()
    val scope=rememberCoroutineScope()
    val context=androidx.compose.ui.platform.LocalContext.current
    var settings by remember { mutableStateOf(false) }
    val resources=mapOf(
        "syncStatusChecking" to cn.super12138.todo.R.string.sync_status_checking,
        "syncStatusCheckingHint" to cn.super12138.todo.R.string.sync_status_checking_hint,
        "syncStatusDisconnected" to cn.super12138.todo.R.string.sync_status_disconnected,
        "syncStatusDisconnectedHint" to cn.super12138.todo.R.string.sync_status_disconnected_hint,
        "syncStatusSyncing" to cn.super12138.todo.R.string.sync_status_syncing,
        "syncStatusSyncingHint" to cn.super12138.todo.R.string.sync_status_syncing_hint,
        "syncStatusSynced" to cn.super12138.todo.R.string.sync_status_synced,
        "syncStatusSyncedHint" to cn.super12138.todo.R.string.sync_status_synced_hint,
        "syncStatusConflict" to cn.super12138.todo.R.string.sync_status_conflict,
        "syncStatusConflictHint" to cn.super12138.todo.R.string.sync_status_conflict_hint,
        "syncStatusOffline" to cn.super12138.todo.R.string.sync_status_offline,
        "syncStatusOfflineHint" to cn.super12138.todo.R.string.sync_status_offline_hint,
        "syncStatusPending" to cn.super12138.todo.R.string.sync_status_pending,
        "syncStatusPendingHint" to cn.super12138.todo.R.string.sync_status_pending_hint,
        "syncStatusUnconfirmed" to cn.super12138.todo.R.string.sync_status_unconfirmed,
        "syncStatusUnconfirmedHint" to cn.super12138.todo.R.string.sync_status_unconfirmed_hint,
        "syncStatusStale" to cn.super12138.todo.R.string.sync_status_stale,
        "syncStatusStaleHint" to cn.super12138.todo.R.string.sync_status_stale_hint,
        "syncStatusError" to cn.super12138.todo.R.string.sync_status_error,
        "syncStatusErrorHint" to cn.super12138.todo.R.string.sync_status_error_hint,
        "syncStatusAction" to cn.super12138.todo.R.string.sync_status_action,
        "syncStatusLastSuccess" to cn.super12138.todo.R.string.sync_status_last_success,
        "syncStatusLastCheck" to cn.super12138.todo.R.string.sync_status_last_check,
        "syncStatusNever" to cn.super12138.todo.R.string.sync_status_never,
        "syncStatusPendingCount" to cn.super12138.todo.R.string.sync_status_pending_count,
        "syncStatusPendingUnknown" to cn.super12138.todo.R.string.sync_status_pending_unknown,
        "syncStatusConflicts" to cn.super12138.todo.R.string.sync_status_conflicts,
        "syncStatusCheck" to cn.super12138.todo.R.string.sync_status_check,
        "syncStatusSettings" to cn.super12138.todo.R.string.sync_status_settings,
        "syncStatusConnect" to cn.super12138.todo.R.string.sync_status_connect,
        "syncStatusRetry" to cn.super12138.todo.R.string.sync_status_retry,
        "syncStatusResolve" to cn.super12138.todo.R.string.sync_status_resolve,
        "syncStatusClose" to cn.super12138.todo.R.string.sync_status_close
    )
    SyncStatusButton(status,{ key -> context.getString(resources.getValue(key)) },
        { scope.launch { repository.synchronize() } },{ settings=true })
    if(settings) cn.super12138.todo.ui.pages.settings.SyncConnectionDialog(repository) { settings=false }
}
