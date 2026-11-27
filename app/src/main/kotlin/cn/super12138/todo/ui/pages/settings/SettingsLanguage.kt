package cn.super12138.todo.ui.pages.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.core.os.LocaleListCompat
import cn.super12138.todo.R
import cn.super12138.todo.ui.pages.settings.components.SettingsItem
import cn.super12138.todo.ui.pages.settings.components.SettingsRadioDialog
import cn.super12138.todo.ui.pages.settings.components.SettingsRadioOptions

@Composable
fun SettingsLanguage() {
    // Read configuration so returning from Android's language settings refreshes the summary.
    val configuration = LocalConfiguration.current
    val locales = AppCompatDelegate.getApplicationLocales()
    val selectedLanguage = locales[0]?.language
    val options = listOf(
        SettingsRadioOptions(0, stringResource(R.string.language_system)),
        SettingsRadioOptions(1, stringResource(R.string.language_english)),
        SettingsRadioOptions(2, stringResource(R.string.language_french))
    )
    val selected = when (selectedLanguage) {
        null -> options[0]
        "en" -> options[1]
        "fr" -> options[2]
        else -> SettingsRadioOptions(3, locales[0]!!.getDisplayName(configuration.locales[0]))
    }
    var showDialog by rememberSaveable { mutableStateOf(false) }

    SettingsItem(
        leadingIconRes = R.drawable.ic_translate,
        title = stringResource(R.string.pref_language),
        description = selected.text,
        onClick = { showDialog = true }
    )
    SettingsRadioDialog(
        visible = showDialog,
        title = stringResource(R.string.pref_language),
        currentOptions = selected,
        options = if (selected.id == 3) options + selected else options,
        onSelect = { id ->
            showDialog = false
            val languageTag = when (id) {
                0 -> ""
                1 -> "en"
                2 -> "fr"
                else -> locales.toLanguageTags()
            }
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        },
        onDismiss = { showDialog = false }
    )
}
