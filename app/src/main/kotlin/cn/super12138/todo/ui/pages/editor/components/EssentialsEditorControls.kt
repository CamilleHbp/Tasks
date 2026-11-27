package cn.super12138.todo.ui.pages.editor.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cn.super12138.todo.R
import cn.super12138.todo.logic.database.Subtask
import cn.super12138.todo.logic.model.Priority
import cn.super12138.todo.ui.components.PriorityIcon

@Composable
fun editorTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant
)

@Composable
fun EditorPropertyRow(label: String, summary: String, @DrawableRes icon: Int,
    expanded: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val expandedDescription = stringResource(if (expanded) R.string.view_expanded else R.string.view_collapsed)
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().semantics { stateDescription = expandedDescription }) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(icon), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Text(summary, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(painterResource(R.drawable.ic_chevron_right), null,
                    Modifier.size(24.dp).rotate(if (expanded) 270f else 90f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
fun EditorSubtaskRow(item: Subtask, index: Int, enabled: Boolean, onChange: (String) -> Unit,
    onCompleted: (Boolean) -> Unit, onNext: () -> Unit, onRemove: () -> Unit) {
    val label = stringResource(R.string.task_subtask_label, index + 1)
    val checkLabel = stringResource(R.string.task_subtask_check, index + 1)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(item.isCompleted, onCheckedChange = onCompleted, enabled = enabled,
            modifier = Modifier.size(48.dp).semantics { contentDescription = checkLabel })
        TextField(value = item.content, onValueChange = onChange, enabled = enabled,
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                textDecoration = if (item.isCompleted) TextDecoration.LineThrough else TextDecoration.None,
                color = if (item.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface),
            placeholder = { Text(label) }, colors = editorTextFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNext() }),
            modifier = Modifier.weight(1f).semantics { contentDescription = label }
                .onPreviewKeyEvent {
                    if (it.key == Key.Enter && !it.isShiftPressed) {
                        if (it.type == KeyEventType.KeyUp) onNext()
                        true
                    } else false
                })
        IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.task_subtask_remove, index + 1))
        }
    }
}

@Composable
fun SubtaskComposer(value: String, onValueChange: (String) -> Unit, onAdd: () -> Unit,
    enabled: Boolean, focusRequester: FocusRequester, itemCount: Int) {
    val intoView = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(focused, itemCount, imeHeight) {
        if (focused) { withFrameNanos { }; intoView.bringIntoView() }
    }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(intoView)) {
        OutlinedTextField(value = value, onValueChange = onValueChange, enabled = enabled, maxLines = 5,
            label = { Text(stringResource(R.string.task_subtask_add)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { if (value.isNotBlank()) onAdd() }),
            trailingIcon = {
                IconButton(onClick = onAdd, enabled = enabled && value.isNotBlank(), modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_add), stringResource(R.string.editor_add_item))
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("subtask-composer").focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent {
                    if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && !it.isShiftPressed) {
                        if (it.type == KeyEventType.KeyUp && value.isNotBlank()) onAdd()
                        true
                    } else false
                })
    }
}

@Composable
fun EditorPriorityChoices(priority: Priority, onChange: (Priority) -> Unit, enabled: Boolean) {
    Column(Modifier.selectableGroup()) {
        Priority.entries.forEach { item ->
            val label = stringResource(R.string.priority_value, item.value.toInt(), stringResource(item.nameRes))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(item == priority, enabled = enabled, role = Role.RadioButton, onClick = { onChange(item) })
                .semantics { contentDescription = label }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(item == priority, onClick = null, enabled = enabled, modifier = Modifier.size(48.dp))
                PriorityIcon(item, contentDescription = null)
                Text(label, Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
fun EditorTagChooser(tags: List<String>, selected: List<String>, colors: Map<String, Int>, query: String,
    onQuery: (String) -> Unit, onToggle: (String) -> Unit, onAdd: () -> Unit, enabled: Boolean) {
    val filtered = (tags + selected).distinct().filter { it.contains(query.trim(), ignoreCase = true) }
    Column(Modifier.testTag("editor-tags-options"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = query, onValueChange = onQuery, enabled = enabled, maxLines = 5,
            label = { Text(stringResource(R.string.editor_find_tag)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (query.isNotBlank()) onAdd() }),
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.editor_clear_tag_search))
                }
            }, modifier = Modifier.fillMaxWidth())
        TagSelectionCloud(filtered, selected.filter { it in filtered }, colors, onToggle, enabled)
        if (query.isNotBlank()) TextButton(onClick = onAdd, enabled = enabled) {
            Text(stringResource(R.string.editor_assign_tag, query.trim()))
        }
    }
}
