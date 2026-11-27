package cn.super12138.todo.ui.widget

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.glance.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow

// Active Glance sessions also need to recompose when the app language changes.
private val widgetLanguage = MutableStateFlow<String?>(null)

fun refreshWidgetLanguage(context: Context) {
    widgetLanguage.value = ContextCompat.getContextForLanguage(context)
        .resources.configuration.locales.toLanguageTags()
}

@Composable
fun LocalizedWidgetContent(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val language by widgetLanguage.collectAsState()
    val localizedContext = remember(context, language) {
        val base = ContextCompat.getContextForLanguage(context)
        if (language == null) base else base.createConfigurationContext(
            Configuration(base.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language))
            }
        )
    }
    CompositionLocalProvider(LocalContext provides localizedContext, content = content)
}
