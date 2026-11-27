package cn.super12138.todo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.ui.components.SyncStatusButton
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import studio.camille.vervetwodo.integration.*
import java.io.File
import java.util.Locale

class SyncStatusTest {
    @get:Rule val compose=createComposeRule()
    private fun text(locale:Locale):(String)->String {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val localized=context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply { setLocale(locale) })
        return { key -> localized.getString(localized.resources.getIdentifier(key.replace(Regex("(?<!^)(?=[A-Z])"),"_").lowercase(),"string",context.packageName)) }
    }
    private fun capture(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null),"status-$name.png").outputStream().use {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    }
    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun mobileTopBarAndDetailsAreActionable() {
        var status by mutableStateOf(SyncReply(SyncState(authenticated=true),error="server_error",telemetry=SyncTelemetry(checked=true)))
        var settings=false
        compose.setContent { MaterialTheme { Scaffold(topBar={ TopAppBar(title={Text("Agenda")},actions={
            SyncStatusButton(status,text(Locale.ENGLISH),{status=status.copy(busy=true)},{settings=true})
        }) }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) } } }
        capture("english-header")
        compose.onNodeWithContentDescription("Sync status: Sync failed").performClick()
        compose.onNodeWithText("Retry sync").performScrollTo().performClick()
        compose.onNodeWithText("Sync failed").assertExists()
        compose.onNodeWithText("Retry sync").assertIsNotEnabled()
        capture("english-error")
        compose.onNodeWithText("Sync settings").performScrollTo().performClick()
        compose.runOnIdle {assertTrue(settings)}
    }
    @Test fun frenchLargeTextAndUnknownReceipt() {
        val state=SyncReply(SyncState(authenticated=true,pending=-1),telemetry=SyncTelemetry(checked=true,lastContactAt=System.currentTimeMillis()))
        compose.setContent { CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.3f)) {
            MaterialTheme(colorScheme=darkColorScheme()) { SyncStatusButton(state,text(Locale.FRENCH),{},{}) }
        } }
        compose.onNodeWithContentDescription("État de synchronisation : Synchronisation non confirmée").performClick()
        compose.onNodeWithText("Modifications en attente : nombre indisponible").performScrollTo().assertExists()
        capture("french-dark")
        compose.onNodeWithText("Fermer").performScrollTo().performClick()
    }
}
