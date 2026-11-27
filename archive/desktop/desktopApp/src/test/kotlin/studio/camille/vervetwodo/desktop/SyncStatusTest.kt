package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import studio.camille.vervetwodo.integration.*
import java.io.File
import java.util.*
import javax.imageio.ImageIO

class SyncStatusTest {
    @get:Rule val compose=createComposeRule()
    private fun text(locale: Locale): (String)->String {
        val bundle=ResourceBundle.getBundle("messages",locale)
        return { bundle.getString(it) }
    }
    private fun capture(name:String) {
        val file=File("../.impeccable/review/sync-status/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(),"png",file)
    }
    private fun good()=SyncReply(SyncState(authenticated=true),telemetry=SyncTelemetry(checked=true,confirmed=true,lastSuccessAt=System.currentTimeMillis()))
    @Test fun headerOpensDetailsAndRetryRetainsError() {
        var status by mutableStateOf(good().copy(error="server_error"))
        var settings=false
        compose.setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) {
            TaskWorkspace(WidgetSnapshot(),text(Locale.ENGLISH),Locale.ENGLISH,false,null,0,0,{_,_->},{},{},
                syncIndicator={ SyncStatusButton(status,text(Locale.ENGLISH),{ status=status.copy(busy=true) },{ settings=true }) })
        } } }
        capture("desktop-header")
        compose.onNodeWithContentDescription("Sync status: Sync failed").performClick()
        compose.onNodeWithText("Retry sync").performClick()
        compose.onNodeWithText("Retry sync").assertIsNotEnabled()
        compose.onNodeWithText("Sync failed").assertExists()
        capture("desktop-error-details")
        compose.onNodeWithText("Sync settings").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(settings) }
    }
    @Test fun confirmedAndUnknownAreDifferentInFrenchDark() {
        var status by mutableStateOf(good())
        compose.setContent { MaterialTheme(colorScheme=darkColorScheme()) {
            Box(Modifier.size(900.dp,700.dp)) { SyncStatusButton(status,text(Locale.FRENCH),{},{}) }
        } }
        compose.onNodeWithContentDescription("État de synchronisation : À jour").performClick()
        compose.onNodeWithText("À jour").assertExists()
        capture("desktop-french-confirmed")
        compose.runOnIdle { status=status.copy(state=status.state.copy(pending=-1)) }
        compose.onNodeWithText("Synchronisation non confirmée").assertExists()
        compose.onNodeWithText("Modifications en attente : nombre indisponible").assertExists()
        capture("desktop-french-unconfirmed")
    }
    @Test fun disconnectedOpensConnectionInsteadOfRetry() {
        var settings=false; var retried=false
        compose.setContent { MaterialTheme { SyncStatusButton(SyncReply(telemetry=SyncTelemetry(checked=true)),text(Locale.ENGLISH),{ retried=true },{ settings=true }) } }
        compose.onNodeWithContentDescription("Sync status: Not connected").performClick()
        compose.onNodeWithText("Connect").performClick()
        compose.runOnIdle { assertTrue(settings); assertFalse(retried) }
    }
}
