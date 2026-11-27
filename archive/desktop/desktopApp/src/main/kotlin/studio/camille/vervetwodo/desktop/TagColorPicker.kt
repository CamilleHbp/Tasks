package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlin.math.*

private fun hsvHex(hue: Float, saturation: Float, brightness: Float) =
    "#%06X".format(java.awt.Color.HSBtoRGB(hue / 360f, saturation, brightness) and 0xffffff)

@Composable
internal fun TagColorPicker(value: String, text: Texts, onChange: (String) -> Unit) {
    val valid = value.matches(Regex("#[0-9a-fA-F]{6}"))
    val initial = remember { java.awt.Color.RGBtoHSB(
        (categoryColor(value).red * 255).roundToInt(),
        (categoryColor(value).green * 255).roundToInt(),
        (categoryColor(value).blue * 255).roundToInt(), null) }
    var hue by remember { mutableStateOf(initial[0] * 360f) }
    var saturation by remember { mutableStateOf(initial[1]) }
    var brightness by remember { mutableStateOf(initial[2]) }
    LaunchedEffect(value) {
        if (valid && !value.equals(hsvHex(hue, saturation, brightness), true)) {
            val rgb = value.drop(1).toInt(16)
            val hsv = java.awt.Color.RGBtoHSB(rgb shr 16, (rgb shr 8) and 255, rgb and 255, null)
            if (hsv[1] > 0f) hue = hsv[0] * 360f
            saturation = hsv[1]
            brightness = hsv[2]
        }
    }
    val selectPoint by rememberUpdatedState<(Offset, Float) -> Unit>({ point, radius ->
        val offset = point - Offset(radius, radius)
        hue = ((atan2(offset.y, offset.x) * 180f / PI.toFloat()) + 360f) % 360f
        saturation = (offset.getDistance() / radius).coerceIn(0f, 1f)
        onChange(hsvHex(hue, saturation, brightness))
    })
    val preview = categoryColor(hsvHex(hue, saturation, brightness))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(Modifier.size(180.dp).align(Alignment.CenterHorizontally)
            .semantics { contentDescription = text("tagColorWheel"); stateDescription = value }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val radius = size.width / 2f
                    if ((down.position - Offset(radius, radius)).getDistance() <= radius) {
                        selectPoint(down.position, radius)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (change.pressed) selectPoint(change.position, radius)
                            change.consume()
                        } while (change.pressed)
                    }
                }
            }) {
            val radius = size.minDimension / 2f
            drawCircle(Brush.sweepGradient(listOf(Color.Red, Color.Yellow, Color.Green,
                Color.Cyan, Color.Blue, Color.Magenta, Color.Red)))
            drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), radius = radius))
            drawCircle(Color.Black.copy(alpha = 1f - brightness))
            val angle = hue * PI.toFloat() / 180f
            val marker = center + Offset(cos(angle), sin(angle)) * (saturation * radius)
            drawCircle(preview, 7.dp.toPx(), marker)
            drawCircle(Color.Black, 8.dp.toPx(), marker, style = Stroke(3.dp.toPx()))
            drawCircle(Color.White, 8.dp.toPx(), marker, style = Stroke(1.5.dp.toPx()))
        }
        Text(text("tagColorBrightness"), style = MaterialTheme.typography.labelMedium)
        Slider(brightness, {
            brightness = it
            onChange(hsvHex(hue, saturation, it))
        }, Modifier.fillMaxWidth().semantics { contentDescription = text("tagColorBrightness") })
        OutlinedTextField(value, onChange, label = { Text(text("tagColor")) }, singleLine = true,
            leadingIcon = { Box(Modifier.size(24.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .background(preview, CircleShape)) },
            isError = !valid, modifier = Modifier.fillMaxWidth())
    }
}
