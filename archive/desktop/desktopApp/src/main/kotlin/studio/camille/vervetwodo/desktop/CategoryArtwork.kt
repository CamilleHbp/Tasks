package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import studio.camille.vervetwodo.integration.CategoryIcons

@Composable
internal fun CategoryArt(icon: String, description: String?, modifier: Modifier = Modifier.size(28.dp), color: String? = null) {
    val id = CategoryIcons.displayId(icon)
    Image(painterResource("camille-icons/$id.png"), description,
        modifier.then(if (color != null) Modifier.border(2.dp, categoryColor(color), CircleShape).padding(4.dp) else Modifier.padding(2.dp))
            .background(Color(0xfff4e6c8), CircleShape))
}

@Composable
internal fun CategoryRing(colors: List<Color>, modifier: Modifier = Modifier.size(24.dp), checked: Boolean = false) {
    val neutral = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val palette = colors.ifEmpty { listOf(neutral) }
        val stroke = 2.5.dp.toPx(); val sweep = CategoryIcons.segmentSweep(palette.size)
        val track = 4.5.dp.toPx()
        val origin = androidx.compose.ui.geometry.Offset(track/2, track/2)
        val bounds = androidx.compose.ui.geometry.Size(size.width-track, size.height-track)
        drawArc(neutral, 0f, 360f, false, topLeft = origin, size = bounds, style = Stroke(track))
        palette.forEachIndexed { index, color -> drawArc(color, -90f + index * sweep, sweep, false,
            topLeft = origin, size = bounds, style = Stroke(stroke)) }
        if (checked) {
            val check = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * 0.30f, size.height * 0.50f)
                lineTo(size.width * 0.44f, size.height * 0.64f)
                lineTo(size.width * 0.70f, size.height * 0.36f)
            }
            drawPath(check, neutral, style = Stroke(2.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
                join = androidx.compose.ui.graphics.StrokeJoin.Round))
        }
    }
}

@Composable
internal fun CategoryPicker(selected: String, color: String, text: Texts, onSelect: (String) -> Unit) {
    Text(text("tagIcon"), style = MaterialTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CategoryIcons.ids.chunked(5).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row.forEach { id -> FilledTonalIconButton(onClick = { onSelect(id) }, modifier = Modifier.size(48.dp)
                .semantics { this.selected = id == selected }, colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (id == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                CategoryArt(id, text("categoryIcon.$id"), Modifier.size(36.dp), color)
            } }
        } }
    }
}
internal fun categoryColor(hex: String): Color = runCatching { Color((hex.removePrefix("#").toLong(16) or 0xff000000L).toInt()) }.getOrDefault(Color(0xff3877b8))
