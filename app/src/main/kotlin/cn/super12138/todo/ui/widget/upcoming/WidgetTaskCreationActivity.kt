package cn.super12138.todo.ui.widget.upcoming

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.super12138.todo.constants.Constants
import cn.super12138.todo.ui.activities.MainViewModel
import cn.super12138.todo.ui.pages.editor.TaskEditorPage
import cn.super12138.todo.ui.theme.VerveDoTheme
import cn.super12138.todo.utils.configureEdgeToEdge
import cn.super12138.todo.utils.isDark
import com.kyant.m3color.dynamiccolor.ColorSpec
import org.koin.compose.viewmodel.koinViewModel
import java.time.LocalDate
import java.time.ZoneId

/** A separate task lets Save and Back return directly to the originating launcher. */
class WidgetTaskCreationActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureEdgeToEdge()
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        setContent {
            val mainViewModel: MainViewModel = koinViewModel()
            val appearance by mainViewModel.appearanceUiState.collectAsStateWithLifecycle()
            val secureMode by mainViewModel.secureModeFlow.collectAsStateWithLifecycle(Constants.PREF_SECURE_MODE_DEFAULT)
            val previewColors by mainViewModel.previewColorSystemFlow.collectAsStateWithLifecycle(Constants.PREF_PREVIEW_COLOR_SYSTEM_DEFAULT)
            LaunchedEffect(secureMode) {
                if (secureMode) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
            VerveDoTheme(
                color = if (appearance.dynamicColor) null else androidx.compose.ui.graphics.Color(appearance.themeColor),
                darkTheme = appearance.darkMode.isDark(),
                pureBlackMode = appearance.pureBlackMode,
                style = appearance.paletteStyle,
                contrastLevel = appearance.contrastLevel,
                dynamicColor = appearance.dynamicColor,
                specVersion = if (previewColors) ColorSpec.SpecVersion.SPEC_2025 else ColorSpec.SpecVersion.SPEC_2021
            ) {
                TaskEditorPage(
                    quickAdd = true,
                    initialCategory = intent.getStringExtra(EXTRA_CATEGORY).orEmpty(),
                    initialDueDateMillis = today,
                    onNavigateUp = { finish() },
                    onSaved = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_CATEGORY = "widget_creation_category"
    }
}
