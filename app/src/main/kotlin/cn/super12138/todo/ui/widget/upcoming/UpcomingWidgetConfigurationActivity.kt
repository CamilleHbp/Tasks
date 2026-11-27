package cn.super12138.todo.ui.widget.upcoming

import cn.super12138.todo.logic.database.taskTags

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import cn.super12138.todo.R
import cn.super12138.todo.logic.SettingsRepository
import cn.super12138.todo.logic.TaskRepository
import cn.super12138.todo.ui.theme.VerveDoTheme
import cn.super12138.todo.utils.isDark
import cn.super12138.todo.utils.configureEdgeToEdge
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get

class UpcomingWidgetConfigurationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()

        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        setResult(RESULT_CANCELED, result)
        val provider = AppWidgetManager.getInstance(this).getAppWidgetInfo(widgetId)?.provider
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID ||
            provider != ComponentName(this, UpcomingTaskWidgetReceiver::class.java)
        ) {
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                val glanceId = GlanceAppWidgetManager(this@UpcomingWidgetConfigurationActivity)
                    .getGlanceIdBy(widgetId)
                val preferences = getAppWidgetState(
                    this@UpcomingWidgetConfigurationActivity,
                    PreferencesGlanceStateDefinition,
                    glanceId
                )
                val initialConfig = UpcomingWidgetPreferences.configuration(preferences)
                val initialCategories = initialConfig.tags
                val settingsRepository: SettingsRepository = get()
                val taskRepository: TaskRepository = get()
                val tasksFlow = taskRepository.getAllTasks()

                val initialAppearance = settingsRepository.appearanceFlow.first()
                setContent {
                    val appearance by settingsRepository.appearanceFlow.collectAsStateWithLifecycle(initialAppearance)
                    val categories by settingsRepository.categoriesFlow.collectAsStateWithLifecycle(emptyList())
                    val tasks by tasksFlow.collectAsStateWithLifecycle(emptyList())
                    val tags = remember(categories, tasks, initialCategories) {
                        (categories + tasks.flatMap { it.taskTags } + initialCategories)
                            .filter { it.isNotBlank() }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
                    }
                    var saving by remember { mutableStateOf(false) }

                    VerveDoTheme(
                        color = if (appearance.dynamicColor) null else androidx.compose.ui.graphics.Color(appearance.themeColor),
                        dynamicColor = appearance.dynamicColor,
                        darkTheme = appearance.darkMode.isDark(),
                        pureBlackMode = appearance.pureBlackMode,
                        style = appearance.paletteStyle,
                        contrastLevel = appearance.contrastLevel,
                        specVersion = if (appearance.previewColorSystem) com.kyant.m3color.dynamiccolor.ColorSpec.SpecVersion.SPEC_2025
                            else com.kyant.m3color.dynamiccolor.ColorSpec.SpecVersion.SPEC_2021
                    ) {
                        WidgetConfiguration(
                            initialConfig = initialConfig,
                            tags = tags,
                            saving = saving,
                            onCancel = { finish() },
                            onSave = { config ->
                                saving = true
                                lifecycleScope.launch {
                                    try {
                                        updateAppWidgetState(this@UpcomingWidgetConfigurationActivity, glanceId) {
                                            UpcomingWidgetPreferences.setConfiguration(it, config)
                                            it.remove(UpcomingWidgetPreferences.panelKey)
                                        }
                                        UpcomingTaskWidget().update(this@UpcomingWidgetConfigurationActivity, glanceId)
                                        setResult(RESULT_OK, result)
                                        finish()
                                    } catch (exception: CancellationException) {
                                        throw exception
                                    } catch (exception: Exception) {
                                        showConfigurationError(exception)
                                        saving = false
                                    }
                                }
                            }
                        )
                    }
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                showConfigurationError(exception)
                finish()
            }
        }
    }

    private fun showConfigurationError(exception: Exception) {
        Log.e("UpcomingWidget", "Unable to configure widget", exception)
        Toast.makeText(this, R.string.widget_settings_error, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun WidgetConfiguration(
    initialConfig: cn.super12138.todo.logic.model.TaskViewConfig,
    tags: List<String>,
    saving: Boolean,
    onCancel: () -> Unit,
    onSave: (cn.super12138.todo.logic.model.TaskViewConfig) -> Unit
) {
    var encoded by rememberSaveable { mutableStateOf(initialConfig.encode()) }
    val config = cn.super12138.todo.logic.model.TaskViewConfig.decode(encoded)
    Scaffold(contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(R.string.action_cancel)) }
                Button(onClick = { onSave(config) }, enabled = !saving) { Text(stringResource(R.string.action_save)) }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)
            .verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text(stringResource(R.string.widget_settings), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 12.dp))
            Text(stringResource(R.string.view_widget_description), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 20.dp))
            cn.super12138.todo.ui.pages.collections.TaskViewOptions(config, tags,
                onChange = { if (!saving) encoded = it.encode() }, allowMode = true)
        }
    }
}
