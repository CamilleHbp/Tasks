package cn.super12138.todo.ui.pages.editor.components

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import cn.super12138.todo.R
import cn.super12138.todo.ui.components.TagArtwork

internal class EditorTagReordering(
    val tags: List<String>,
    private val onReorder: (List<String>) -> Unit
) {
    private data class Drag(val tag: String, val start: Offset, val offset: Offset = Offset.Zero)

    private val coordinates = mutableMapOf<String, LayoutCoordinates>()
    private var drag by mutableStateOf<Drag?>(null)

    private fun target(): String? {
        val current = drag ?: return null
        val pointer = current.start + current.offset
        return tags.firstOrNull { tag ->
            tag != current.tag && coordinates[tag]?.takeIf { it.isAttached }
                ?.boundsInRoot()?.contains(pointer) == true
        }
    }

    fun retainVisibleTags(visibleTags: List<String>) {
        coordinates.keys.retainAll(visibleTags.toSet())
    }

    fun record(tag: String, layout: LayoutCoordinates) {
        coordinates[tag] = layout
    }

    fun start(tag: String, point: Offset) {
        coordinates[tag]?.takeIf { it.isAttached }?.let {
            drag = Drag(tag, it.localToRoot(point))
        }
    }

    fun move(amount: Offset) {
        drag = drag?.let { it.copy(offset = it.offset + amount) }
    }

    fun drop() {
        val current = drag ?: return
        val target = target()
        cancel()
        if (target != null) moveTo(current.tag, tags.indexOf(target))
    }

    fun cancel() {
        drag = null
    }

    fun offset(tag: String): Offset = drag?.takeIf { it.tag == tag }?.offset ?: Offset.Zero
    fun isDragging(tag: String): Boolean = drag?.tag == tag
    fun isTarget(tag: String): Boolean = target() == tag

    fun moveTo(tag: String, index: Int): Boolean {
        if (index !in tags.indices || tags[index] == tag) return false
        onReorder(tags.toMutableList().apply {
            remove(tag)
            add(index, tag)
        })
        return true
    }
}

@Composable
internal fun EditorTagChip(
    tag: String,
    selected: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean,
    reordering: EditorTagReordering?,
    singleLine: Boolean = false
) {
    val haptics = LocalHapticFeedback.current
    val dragModifier = if (reordering == null) Modifier else Modifier
        .onGloballyPositioned { reordering.record(tag, it) }
        .zIndex(if (reordering.isDragging(tag)) 1f else 0f)
        .pointerInput(tag, reordering, haptics) {
            detectDragGesturesAfterLongPress(
                onDragStart = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    reordering.start(tag, it)
                },
                onDrag = { change, amount ->
                    change.consume()
                    reordering.move(amount)
                },
                onDragEnd = reordering::drop,
                onDragCancel = reordering::cancel
            )
        }
    val chipModifier = if (reordering == null) Modifier else {
        val earlier = stringResource(R.string.tag_move_earlier)
        val later = stringResource(R.string.tag_move_later)
        val index = reordering.tags.indexOf(tag)
        Modifier.graphicsLayer {
            val offset = reordering.offset(tag)
            translationX = offset.x
            translationY = offset.y
        }.semantics {
            customActions = buildList {
                if (index > 0) add(CustomAccessibilityAction(earlier) { reordering.moveTo(tag, index - 1) })
                if (index < reordering.tags.lastIndex) {
                    add(CustomAccessibilityAction(later) { reordering.moveTo(tag, index + 1) })
                }
            }
        }.then(
            if (reordering.isTarget(tag)) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, FilterChipDefaults.shape)
            else Modifier
        )
    }
    Box(dragModifier) {
        FilterChip(
            selected = selected, enabled = enabled, onClick = onToggle,
            label = {
                if (singleLine) Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Text(tag)
            },
            leadingIcon = { TagArtwork(tag, Modifier.size(28.dp)) },
            trailingIcon = if (selected) {{ Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp)) }} else null,
            modifier = chipModifier.heightIn(min = 48.dp)
        )
    }
}
