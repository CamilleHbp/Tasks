package cn.super12138.todo

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.ui.activities.MainActivity
import org.junit.Assert.*
import org.junit.Test
import studio.camille.updater.CamilleUpdater
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

class UpdateCheckTest {
    @Test fun manualCheckDoesNotCreateDeadlines() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("camille-updater", 0)
        prefs.edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { CamilleUpdater.checkNow(it) }
            val io = Class.forName("studio.camille.updater.Updater").getDeclaredField("IO")
                .apply { isAccessible = true }.get(null) as ExecutorService
            io.submit {}.get(45, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            assertFalse(prefs.contains("snooze"))
            assertFalse(prefs.contains("nextCheck"))
            assertFalse(prefs.contains("nextCheckV2"))
        }
    }
}
