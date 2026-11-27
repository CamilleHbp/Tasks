package cn.super12138.todo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.super12138.todo.R
import cn.super12138.todo.logic.assignTagColors
import cn.super12138.todo.logic.TagRepository
import org.koin.compose.koinInject
import studio.camille.vervetwodo.integration.CategoryIcons

fun categoryDrawable(id: String?): Int = cn.super12138.todo.SharedCategoryIcons.drawable(CategoryIcons.displayId(id))

fun categoryLabel(id: String): Int = cn.super12138.todo.SharedCategoryIcons.label(id)

@Composable
fun CategoryArtwork(icon: String?, description: String? = null, modifier: Modifier = Modifier.size(28.dp)) {
    Image(painterResource(categoryDrawable(icon)), description,
        modifier.background(Color(0xfff4e6c8), CircleShape).padding(2.dp))
}

@Composable
fun TagArtwork(tag: String, modifier: Modifier = Modifier.size(28.dp), repository: TagRepository = koinInject()) {
    val icons by repository.icons.collectAsStateWithLifecycle(emptyMap())
    CategoryArtwork(icons[tag], tag, modifier)
}

@Composable
fun CategoryIconPicker(selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Text(stringResource(R.string.category_icon), style = MaterialTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CategoryIcons.ids.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { id ->
                    FilledTonalIconButton(onClick = { onSelect(id) }, enabled = enabled,
                        modifier = Modifier.size(52.dp).semantics { this.selected = selected == id },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor =
                            if (selected == id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                        CategoryArtwork(id, stringResource(categoryLabel(id)), Modifier.size(36.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryCompletionRing(colors: List<Color>, modifier: Modifier = Modifier.size(24.dp)) {
    val neutral = MaterialTheme.colorScheme.onSurface
    Canvas(modifier) {
        val palette = colors.ifEmpty { listOf(neutral) }
        val stroke = 2.5.dp.toPx()
        val track = 4.5.dp.toPx()
        val origin = androidx.compose.ui.geometry.Offset(track / 2, track / 2)
        val bounds = androidx.compose.ui.geometry.Size(size.width - track, size.height - track)
        drawArc(neutral, 0f, 360f, false, topLeft = origin, size = bounds, style = Stroke(track))
        val sweep = CategoryIcons.segmentSweep(palette.size)
        palette.forEachIndexed { i, color ->
            drawArc(color, -90f + i * sweep, sweep, false,
                topLeft = origin, size = bounds, style = Stroke(stroke))
        }
    }
}

@Composable
fun CategoryIconStrip(tags: List<String>, onShowAll: () -> Unit, repository: TagRepository = koinInject(), modifier: Modifier = Modifier) {
    val icons by repository.icons.collectAsStateWithLifecycle(emptyMap())
    val colors by repository.colors.collectAsStateWithLifecycle(emptyMap())
    val resolved = assignTagColors(tags, colors)
    if (tags.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = modifier.heightIn(min = 48.dp)
            .clickable(onClick = onShowAll).padding(horizontal = 4.dp)
            .semantics { contentDescription = tags.joinToString(", ") }) {
        tags.take(2).forEach { tag ->
            // Keep the illustration at its original size inside a separate colour rim.
            Box(Modifier.size(36.dp)
                .border(0.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .padding(0.5.dp)
                .border(2.dp, Color(resolved.getValue(tag)).copy(alpha = 1f), CircleShape),
                contentAlignment = Alignment.Center) {
                CategoryArtwork(icons[tag], modifier = Modifier.size(28.dp))
            }
        }
        if (tags.size > 2) Text("+${tags.size - 2}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
