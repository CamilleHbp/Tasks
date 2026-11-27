package cn.super12138.todo.ui.widget.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.glance.*
import androidx.glance.layout.*
import androidx.glance.appwidget.cornerRadius
import androidx.glance.semantics.*
import androidx.glance.text.Text
import cn.super12138.todo.logic.assignTagColors
import cn.super12138.todo.logic.TagRepository
import cn.super12138.todo.ui.components.categoryDrawable
import org.koin.core.context.GlobalContext
import studio.camille.vervetwodo.integration.CategoryIcons

@Composable
fun GlanceCategoryIcons(tags: List<String>) {
    if (tags.isEmpty()) return
    val repository = remember { GlobalContext.get().get<TagRepository>() }
    val icons by repository.icons.collectAsState(emptyMap())
    val colors by repository.colors.collectAsState(emptyMap())
    val resolved = assignTagColors(tags, colors)
    val context = LocalContext.current
    val outline = GlanceTheme.colors.outline.getColor(context).toArgb()
    Row(GlanceModifier.semantics { contentDescription = tags.joinToString(", ") },
        verticalAlignment = Alignment.CenterVertically) {
        tags.take(2).forEach { tag ->
            val rimColor = resolved.getValue(tag) or 0xff000000.toInt()
            val rim = remember(rimColor, outline) {
                Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888).apply {
                    val canvas = Canvas(this)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeWidth = 2f
                        color = outline
                    }
                    canvas.drawCircle(72f, 72f, 71f, paint)
                    paint.strokeWidth = 8f
                    paint.color = rimColor
                    canvas.drawCircle(72f, 72f, 66f, paint)
                }
            }
            Box(GlanceModifier.size(36.dp), contentAlignment = Alignment.Center) {
                Image(ImageProvider(rim), null, modifier = GlanceModifier.size(36.dp))
                Image(ImageProvider(categoryDrawable(icons[tag])), tag,
                    modifier = GlanceModifier.size(28.dp).background(androidx.compose.ui.graphics.Color(0xfff4e6c8)).cornerRadius(14.dp).padding(2.dp))
            }
            Spacer(GlanceModifier.width(6.dp))
        }
        if (tags.size > 2) Text("+${tags.size - 2}",
            style = cn.super12138.todo.utils.GlanceTypography.labelMedium.copy(color = GlanceTheme.colors.onSecondaryContainer),
            modifier = GlanceModifier.background(GlanceTheme.colors.secondaryContainer).cornerRadius(12.dp)
                .padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
fun GlanceCategoryRing(colors: List<Int>, description: String) {
    val context = LocalContext.current
    val neutral = GlanceTheme.colors.onSurface.getColor(context).toArgb()
    val bitmap = remember(colors, neutral) {
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            val palette = colors.ifEmpty { listOf(neutral) }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 18f; color = neutral }
            val bounds = RectF(9f,9f,87f,87f)
            canvas.drawArc(bounds, 0f, 360f, false, paint)
            paint.strokeWidth = 10f
            val sweep = CategoryIcons.segmentSweep(palette.size)
            palette.forEachIndexed { index, color -> paint.color = color; canvas.drawArc(bounds, -90f+index*sweep, sweep, false, paint) }
        }
    }
    Image(ImageProvider(bitmap), description, modifier = GlanceModifier.size(24.dp))
}
