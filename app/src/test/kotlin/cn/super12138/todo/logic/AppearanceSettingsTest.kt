package cn.super12138.todo.logic

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import cn.super12138.todo.logic.datastore.DataStoreManager
import cn.super12138.todo.logic.model.AppearanceSettings
import cn.super12138.todo.logic.model.DEFAULT_THEME_COLOR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppearanceSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun customColorAndTransparencySurviveReopeningPreferences() = runBlocking {
        val file = folder.newFolder().resolve("appearance.preferences_pb")
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val manager = DataStoreManager(PreferenceDataStoreFactory.create(scope = scope) { file })
        try {
            assertEquals(AppearanceSettings(), manager.appearanceFlow.first())
            manager.setThemeColor(0x006750A4)
            manager.setWidgetTransparency(65)
            val custom = manager.appearanceFlow.first()
            assertEquals(0xFF6750A4.toInt(), custom.themeColor)
            assertFalse(custom.dynamicColor)
            assertEquals(0.35f, custom.widgetBackgroundAlpha, 0.001f)
            manager.setDynamicColor(true)
            assertEquals(custom.themeColor, manager.appearanceFlow.first().themeColor)
        } finally {
            scope.cancel()
            job.join()
        }
        val reopenedScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val reopened = DataStoreManager(PreferenceDataStoreFactory.create(scope = reopenedScope) { file })
            val saved = reopened.appearanceFlow.first()
            assertEquals(0xFF6750A4.toInt(), saved.themeColor)
            assertEquals(65, saved.widgetTransparency)
            assertTrue(saved.dynamicColor)
        } finally { reopenedScope.cancel() }
    }

    @Test fun transparencyBoundsAndLegacyPreferencesAreSafe() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val file = folder.newFolder().resolve("legacy.preferences_pb")
        val manager = DataStoreManager(PreferenceDataStoreFactory.create(scope = scope) { file })
        try {
            assertEquals(DEFAULT_THEME_COLOR, manager.appearanceFlow.first().themeColor)
            assertEquals(1f, manager.appearanceFlow.first().widgetBackgroundAlpha)
            manager.setWidgetTransparency(150)
            assertEquals(0f, manager.appearanceFlow.first().widgetBackgroundAlpha)
            manager.setWidgetTransparency(-10)
            assertEquals(1f, manager.appearanceFlow.first().widgetBackgroundAlpha)
            manager.dataStore.edit { it[intPreferencesKey("widget_transparency")] = 999 }
            assertEquals(100, manager.appearanceFlow.first().widgetTransparency)
        } finally { scope.cancel() }
    }
}
