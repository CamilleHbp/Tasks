package cn.super12138.todo.ui.pages.settings.components.appearance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import cn.super12138.todo.R
import cn.super12138.todo.logic.model.AppearanceSettings
import cn.super12138.todo.ui.components.TagColorPicker
import cn.super12138.todo.ui.components.parseTagColor
import cn.super12138.todo.ui.components.tagColorHex
import cn.super12138.todo.ui.pages.settings.components.MoreContentSettingsItem
import cn.super12138.todo.ui.theme.VerveDoTheme
import cn.super12138.todo.utils.isDark
import com.kyant.m3color.dynamiccolor.ColorSpec
import kotlin.math.roundToInt

private val themePresets = listOf(
    R.string.theme_ocean to 0xFF0061A4.toInt(),
    R.string.theme_forest to 0xFF386A20.toInt(),
    R.string.theme_teal to 0xFF006A6A.toInt(),
    R.string.theme_lavender to 0xFF6750A4.toInt(),
    R.string.theme_rose to 0xFF984061.toInt(),
    R.string.theme_sunset to 0xFF9C432E.toInt()
)

@Composable
fun AccentColorPicker(appearance: AppearanceSettings, onColorChange: (Int) -> Unit) {
    var showCustom by rememberSaveable { mutableStateOf(false) }
    MoreContentSettingsItem(
        title = stringResource(R.string.appearance_accent),
        description = stringResource(R.string.appearance_accent_description)
    ) {
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            themePresets.forEach { (name, color) ->
                FilterChip(
                    selected = !appearance.dynamicColor && appearance.themeColor == color,
                    onClick = { onColorChange(color) },
                    label = { Text(stringResource(name)) },
                    leadingIcon = { Box(Modifier.size(18.dp).background(Color(color), CircleShape)) }
                )
            }
        }
        OutlinedButton(onClick = { showCustom = true }) {
            Text(stringResource(R.string.appearance_custom_color))
        }
        if (!appearance.dynamicColor) {
            Text(stringResource(R.string.appearance_selected_color, tagColorHex(appearance.themeColor)),
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (showCustom) {
        var hex by rememberSaveable { mutableStateOf(tagColorHex(appearance.themeColor)) }
        val color = parseTagColor(hex)
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text(stringResource(R.string.appearance_custom_color)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    TagColorPicker(hex = hex, label = stringResource(R.string.appearance_hex_color), onChange = { hex = it })
                    VerveDoTheme(
                        color = Color(color ?: appearance.themeColor),
                        darkTheme = appearance.darkMode.isDark(), pureBlackMode = appearance.pureBlackMode,
                        style = appearance.paletteStyle, contrastLevel = appearance.contrastLevel,
                        specVersion = if (appearance.previewColorSystem) ColorSpec.SpecVersion.SPEC_2025 else ColorSpec.SpecVersion.SPEC_2021
                    ) { ThemeSample() }
                }
            },
            confirmButton = {
                TextButton(enabled = color != null, onClick = {
                    color?.let(onColorChange)
                    showCustom = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text(stringResource(R.string.action_cancel)) } }
        )
    }
}

@Composable
private fun ThemeSample(modifier: Modifier = Modifier, alpha: Float = 1f) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = alpha),
        contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.appearance_preview), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.appearance_preview_task), style = MaterialTheme.typography.bodyLarge)
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text(stringResource(R.string.action_add_task), style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun WidgetTransparencyPicker(percent: Int, onPercentChange: (Int) -> Unit) {
    var draft by rememberSaveable(percent) { mutableFloatStateOf(percent.toFloat()) }
    val label = stringResource(R.string.appearance_widget_transparency)
    MoreContentSettingsItem(title = label, description = stringResource(R.string.appearance_widget_transparency_description)) {
        Text(stringResource(R.string.appearance_transparency_percent, draft.roundToInt()),
            style = MaterialTheme.typography.titleMedium)
        Slider(value = draft, onValueChange = { draft = it }, valueRange = 0f..100f,
            onValueChangeFinished = { onPercentChange(draft.roundToInt()) },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = label })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.appearance_opaque), style = MaterialTheme.typography.labelMedium)
            Text(stringResource(R.string.appearance_transparent), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))) {
            Canvas(Modifier.matchParentSize()) {
                val tile = 16.dp.toPx()
                for (row in 0..(size.height / tile).toInt()) {
                    for (column in 0..(size.width / tile).toInt()) {
                        drawRect(if ((row + column) % 2 == 0) Color(0xFFB5B5B5) else Color(0xFF858585),
                            topLeft = Offset(column * tile, row * tile), size = Size(tile, tile))
                    }
                }
            }
            ThemeSample(alpha = 1f - draft / 100f)
        }
        TextButton(onClick = { draft = 0f; onPercentChange(0) }, enabled = draft != 0f) {
            Text(stringResource(R.string.appearance_reset_transparency))
        }
    }
}
