package cn.super12138.todo

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.LocaleManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.glance.appwidget.compose
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.ui.activities.MainActivity
import cn.super12138.todo.logic.model.TaskViewConfig
import cn.super12138.todo.logic.model.TaskViewMode
import cn.super12138.todo.ui.widget.refreshWidgetLanguage
import cn.super12138.todo.ui.widget.upcoming.UpcomingTaskWidget
import cn.super12138.todo.ui.widget.upcoming.UpcomingWidgetPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class AppLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun settingsSwitchBothLanguagesPersistAndReturnToSystem() {
        val original = LocaleManagerCompat.getApplicationLocales(context)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity {
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                }
                compose.waitForIdle()
                compose.onNodeWithContentDescription("More options").performClick()
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithText("Language").performClick()
                compose.onNodeWithText("Français").performClick()
                compose.waitForIdle()
                compose.onNodeWithText("Langue").assertExists()
                scenario.onActivity {
                    assertEquals("fr", it.resources.configuration.locales[0].language)
                    assertEquals("Sous-tâches", it.getString(R.string.task_subtasks))
                    assertEquals("En retard", it.getString(R.string.overview_overdue))
                    assertEquals("Étiquettes", it.getString(R.string.view_tags))
                }
                scenario.recreate()
                compose.onNodeWithText("Langue").assertExists()
                assertEquals("fr", LocaleManagerCompat.getApplicationLocales(context)[0]?.language)
                assertEquals("Vue des tâches", ContextCompat.getContextForLanguage(context).getString(R.string.view_widget_title))
                assertWidgetLanguage("Étiquettes")

                compose.onNodeWithText("Langue").performClick()
                compose.onNodeWithText("English").performClick()
                compose.waitForIdle()
                compose.onNodeWithText("Language").assertExists()
                scenario.onActivity {
                    assertEquals("en", it.resources.configuration.locales[0].language)
                    assertEquals("Subtasks", it.getString(R.string.task_subtasks))
                }
                assertWidgetLanguage("Tags")
                compose.onNodeWithText("Language").performClick()
                compose.onNodeWithText("Follow system").performClick()
                compose.waitForIdle()
                assertTrue(LocaleManagerCompat.getApplicationLocales(context).isEmpty)
                scenario.onActivity {
                    val expected = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                        setLocales(android.os.LocaleList.forLanguageTags(LocaleManagerCompat.getSystemLocales(context).toLanguageTags()))
                    })
                    assertEquals(expected.getString(R.string.page_settings), it.getString(R.string.page_settings))
                }
            }
        } finally {
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(original) }
        }
    }

    private fun assertWidgetLanguage(expectedTitle: String) {
        refreshWidgetLanguage(context)
        val state = mutablePreferencesOf().also {
            UpcomingWidgetPreferences.setConfiguration(it, TaskViewConfig(mode = TaskViewMode.Tags))
        }
        val remoteViews = runBlocking {
            UpcomingTaskWidget().compose(context, size = DpSize(340.dp, 620.dp), state = state)
        }
        instrumentation.runOnMainSync {
            fun texts(view: View): List<String> = when (view) {
                is TextView -> listOf(view.text.toString())
                is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                else -> emptyList()
            }
            assertTrue(texts(remoteViews.apply(context, FrameLayout(context))).contains(expectedTitle))
        }
    }

    @Test fun frenchRegionsAndUnsupportedLanguagesResolveCorrectly() {
        fun localized(tag: String) = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        )
        listOf("fr-FR", "fr-CA").forEach { tag ->
            assertEquals("Paramètres", localized(tag).getString(R.string.page_settings))
            assertEquals("1 tâche", localized(tag).resources.getQuantityString(R.plurals.overview_task_count, 1, 1))
            assertEquals("2 tâches", localized(tag).resources.getQuantityString(R.plurals.overview_task_count, 2, 2))
        }
        assertEquals("Settings", localized("en-GB").getString(R.string.page_settings))
        assertEquals("Settings", localized("es-ES").getString(R.string.page_settings))
    }
}
