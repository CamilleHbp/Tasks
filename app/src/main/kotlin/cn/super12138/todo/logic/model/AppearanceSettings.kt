package cn.super12138.todo.logic.model

import cn.super12138.todo.constants.Constants

const val DEFAULT_THEME_COLOR: Int = 0xFF0061A4.toInt()

data class AppearanceSettings(
    val dynamicColor: Boolean = Constants.PREF_DYNAMIC_COLOR_DEFAULT,
    val paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    val darkMode: DarkMode = DarkMode.FollowSystem,
    val pureBlackMode: Boolean = Constants.PREF_PURE_BLACK_MODE_DEFAULT,
    val contrastLevel: ContrastLevel = ContrastLevel.Default,
    val previewColorSystem: Boolean = Constants.PREF_PREVIEW_COLOR_SYSTEM_DEFAULT,
    val themeColor: Int = DEFAULT_THEME_COLOR,
    val widgetTransparency: Int = 0
) {
    val widgetBackgroundAlpha: Float get() = 1f - widgetTransparency.coerceIn(0, 100) / 100f
}
