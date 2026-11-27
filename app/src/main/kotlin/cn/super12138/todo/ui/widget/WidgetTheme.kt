package cn.super12138.todo.ui.widget

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.color.ColorProvider
import androidx.glance.material3.ColorProviders
import cn.super12138.todo.logic.model.AppearanceSettings
import cn.super12138.todo.logic.model.DarkMode
import cn.super12138.todo.logic.model.DEFAULT_THEME_COLOR
import cn.super12138.todo.ui.theme.dynamicColorScheme
import com.kyant.m3color.dynamiccolor.ColorSpec

val LocalWidgetBackgroundAlpha = staticCompositionLocalOf { 1f }
val LocalWidgetBackground = staticCompositionLocalOf<androidx.glance.unit.ColorProvider> {
    androidx.glance.unit.ColorProvider(Color.Transparent)
}

internal fun widgetThemeColors(context: Context, appearance: AppearanceSettings) = run {
    val seed = if (appearance.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Color(context.getColor(android.R.color.system_accent1_500))
    } else Color(if (appearance.dynamicColor) DEFAULT_THEME_COLOR else appearance.themeColor)
    val spec = if (appearance.previewColorSystem) ColorSpec.SpecVersion.SPEC_2025 else ColorSpec.SpecVersion.SPEC_2021
    fun scheme(dark: Boolean) = dynamicColorScheme(
        keyColor = seed, isDark = dark, pureBlack = appearance.pureBlackMode,
        style = appearance.paletteStyle, contrastLevel = appearance.contrastLevel.value.toDouble(),
        specVersion = spec
    )
    val light = scheme(appearance.darkMode == DarkMode.Dark)
    val dark = scheme(appearance.darkMode != DarkMode.Light)
    light to dark
}

@Composable
fun VerveDoWidgetTheme(appearance: AppearanceSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val (light, dark) = remember(context, appearance) { widgetThemeColors(context, appearance) }
    val alpha = appearance.widgetBackgroundAlpha
    val background = ColorProvider(
        day = light.surface.copy(alpha = alpha),
        night = dark.surface.copy(alpha = alpha)
    )
    CompositionLocalProvider(LocalWidgetBackground provides background, LocalWidgetBackgroundAlpha provides alpha) {
        GlanceTheme(colors = ColorProviders(light = light, dark = dark), content = content)
    }
}
