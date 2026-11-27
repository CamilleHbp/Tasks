package cn.super12138.todo.ui.pages.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.super12138.todo.R
import cn.super12138.todo.ui.components.TagManagementDialog
import cn.super12138.todo.ui.components.TagDeleteDialog
import cn.super12138.todo.ui.pages.settings.SettingsDataCategoryViewModel
import cn.super12138.todo.utils.VibrationUtils
import org.koin.compose.viewmodel.koinViewModel
import java.text.Collator
import java.text.NumberFormat

@Composable
fun TagCloudPage(
    toTasks: (String) -> Unit,
    toSettings: () -> Unit,
    viewModel: TagCloudViewModel = koinViewModel(),
    tagEditorViewModel: SettingsDataCategoryViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val error = stringResource(R.string.tag_change_failed)
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel, error) { viewModel.errors.collect { snackbar.showSnackbar(error) } }
    TagCloudContent(state, toTasks, viewModel::setFavorite, viewModel::reorderTags,
        { editing = it }, { deleting = it }, toSettings, viewModel::dismissGestureHint, snackbar, syncIndicator = { cn.super12138.todo.ui.components.AppSyncStatusButton() })
    editing?.let { tag ->
        TagManagementDialog(tag, onDismiss = { editing = null }, initialColor = state.colors[tag],
            viewModel = tagEditorViewModel)
    }
    deleting?.let { tag ->
        TagDeleteDialog(tag, onDismiss = { deleting = null }, viewModel = tagEditorViewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagCloudContent(
    state: TagCloudState,
    onOpen: (String) -> Unit,
    onFavorite: (String, Boolean) -> Unit,
    onReorder: (List<String>) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
    toSettings: () -> Unit,
    onDismissHint: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    syncIndicator: @Composable () -> Unit = {}
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val names = state.tags.filter { it.contains(query.trim(), ignoreCase = true) }
    val untagged = stringResource(R.string.widget_no_tag)
    val showUntagged = state.untaggedCount > 0 && untagged.contains(query.trim(), ignoreCase = true)
    val createLabel = stringResource(R.string.tag_create)
    val scroll = rememberScrollState()
    BackHandler(searching) { searching = false; query = "" }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.view_tags), style = MaterialTheme.typography.headlineSmall) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                actions = {
                    IconButton(onClick = { searching = !searching; if (!searching) query = "" }) {
                        Icon(painterResource(R.drawable.ic_search), stringResource(R.string.tag_search))
                    }
                    syncIndicator()
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.view_more))
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            if (state.manualOrder) DropdownMenuItem(
                                text = { Text(stringResource(R.string.tag_reset_order)) },
                                onClick = { menu = false; onReorder(emptyList()) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.page_settings)) },
                                onClick = { menu = false; toSettings() })
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit("") }, shape = MaterialTheme.shapes.large,
                containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Icon(painterResource(R.drawable.ic_add), createLabel)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 840.dp).fillMaxSize().verticalScroll(scroll)
                .padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 112.dp)) {
                if (searching) {
                    OutlinedTextField(query, { query = it }, singleLine = true,
                        label = { Text(stringResource(R.string.tag_search)) },
                        trailingIcon = {
                            IconButton(onClick = { searching = false; query = "" }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close))
                            }
                        }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                }
                if (!state.loaded) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else if (names.isEmpty() && !showUntagged) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(if (query.isBlank()) R.string.tag_empty else R.string.tag_search_empty),
                            style = MaterialTheme.typography.titleMedium)
                        if (query.isBlank()) Text(stringResource(R.string.tag_empty_description),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else TextButton(onClick = { query = "" }) { Text(stringResource(R.string.tag_search_clear)) }
                    }
                } else {
                    if (names.isNotEmpty()) {
                        if (state.showGestureHint) {
                            Surface(color = MaterialTheme.colorScheme.surfaceContainer,
                                shape = MaterialTheme.shapes.medium) {
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(stringResource(R.string.tag_cloud_hint),
                                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    IconButton(onClick = onDismissHint) {
                                        Icon(painterResource(R.drawable.ic_close),
                                            stringResource(R.string.tag_cloud_hint_dismiss))
                                    }
                                }
                            }
                            Spacer(Modifier.height(20.dp))
                        }
                        TagCloud(names, state, onOpen, onFavorite, onReorder, onEdit, onDelete)
                    }
                    if (showUntagged) {
                        Spacer(Modifier.height(24.dp))
                        OutlinedButton(onClick = { onOpen("") }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(untagged, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(12.dp))
                            Text(pluralStringResource(R.plurals.tag_task_count, state.untaggedCount, state.untaggedCount))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagCloud(
    tags: List<String>,
    state: TagCloudState,
    onOpen: (String) -> Unit,
    onFavorite: (String, Boolean) -> Unit,
    onReorder: (List<String>) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val positions = remember { mutableMapOf<String, Rect>() }
    var drag by remember { mutableStateOf<TagDrag?>(null) }
    val target = drag?.let { current ->
        tags.filter { it in positions }.minByOrNull {
            (positions.getValue(it).center - current.pointer).getDistanceSquared()
        }
    }
    fun move(tag: String, index: Int) {
        val order = state.tags.toMutableList()
        if (tag in order && index in order.indices) {
            order.remove(tag)
            order.add(index, tag)
            onReorder(order)
        }
    }
    LaunchedEffect(tags) {
        positions.keys.retainAll(tags.toSet())
        if (drag?.tag !in tags) drag = null
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            key(tag) {
                val index = tags.indexOf(tag)
                val currentDrag = drag?.takeIf { it.tag == tag }
                TagCloudPill(tag, state.counts[tag] ?: 0, state.colors[tag]?.let(::Color)
                    ?: MaterialTheme.colorScheme.secondary, tag in state.favorites,
                    onOpen = { onOpen(tag) }, onFavorite = { onFavorite(tag, tag !in state.favorites) },
                    onEdit = { onEdit(tag) },
                    onDelete = { onDelete(tag) },
                    onMoveEarlier = if (index > 0) ({ move(tag, state.tags.indexOf(tags[index - 1])) }) else null,
                    onMoveLater = if (index < tags.lastIndex)
                        ({ move(tag, state.tags.indexOf(tags[index + 1])) }) else null,
                    onBounds = { positions[tag] = it },
                    dragOffset = currentDrag?.let {
                        it.origin + it.delta - (positions[tag]?.topLeft ?: it.origin)
                    } ?: Offset.Zero,
                    dragging = currentDrag != null,
                    dropTarget = target == tag && drag?.tag != tag,
                    dragActions = TagDragActions(
                        start = { position -> positions[tag]?.let { bounds ->
                            drag = TagDrag(tag, bounds.topLeft, bounds.topLeft + position)
                        } },
                        move = { delta -> drag = drag?.let { it.copy(delta = it.delta + delta, pointer = it.pointer + delta) } },
                        drop = {
                            if (target != null && target != tag) move(tag, state.tags.indexOf(target))
                            drag = null
                        },
                        cancel = { drag = null }
                    ))
            }
        }
    }
}

private data class TagDrag(val tag: String, val origin: Offset, val pointer: Offset, val delta: Offset = Offset.Zero)
private data class TagDragActions(
    val start: (Offset) -> Unit, val move: (Offset) -> Unit, val drop: () -> Unit, val cancel: () -> Unit
)

@Composable
private fun TagCloudPill(
    name: String,
    count: Int,
    color: Color,
    favorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveEarlier: (() -> Unit)?,
    onMoveLater: (() -> Unit)?,
    onBounds: (Rect) -> Unit,
    dragOffset: Offset,
    dragging: Boolean,
    dropTarget: Boolean,
    dragActions: TagDragActions
) {
    var menu by remember { mutableStateOf(false) }
    val view = LocalView.current
    val background = color.copy(alpha = 1f)
    // Either black or white always provides at least 4.5:1 against an opaque tag colour.
    val foreground = if (background.luminance() > 0.179f) Color.Black else Color.White
    val description = stringResource(R.string.tag_pill_description, name,
        pluralStringResource(R.plurals.tag_task_count, count, count))
    val favoriteLabel = stringResource(if (favorite) R.string.tag_favorite_remove else R.string.tag_favorite_add)
    val editLabel = stringResource(R.string.tag_edit)
    val deleteLabel = stringResource(R.string.tag_delete)
    val favoriteState = stringResource(R.string.tag_favorite_state)
    val actionsLabel = stringResource(R.string.tag_actions)
    val moveEarlierLabel = stringResource(R.string.tag_move_earlier)
    val moveLaterLabel = stringResource(R.string.tag_move_later)
    val interactions = remember { MutableInteractionSource() }
    val currentDragActions by rememberUpdatedState(dragActions)
    val locale = LocalConfiguration.current.locales[0]
    val number = remember(count, locale) { NumberFormat.getIntegerInstance(locale).format(count) }
    Box(
        Modifier.onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .zIndex(if (dragging) 1f else 0f)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button,
                onClickLabel = stringResource(R.string.tag_open_tasks), onClick = onOpen)
            .pointerInput(name, favorite) {
                var movement = Offset.Zero
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        movement = Offset.Zero
                        VibrationUtils.performHapticFeedback(view)
                        currentDragActions.start(it)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        movement += amount
                        currentDragActions.move(amount)
                    },
                    onDragEnd = {
                        if (movement.getDistance() > viewConfiguration.touchSlop) currentDragActions.drop()
                        else { currentDragActions.cancel(); menu = true }
                    },
                    onDragCancel = { currentDragActions.cancel() }
                )
            }
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    if (favorite) stateDescription = favoriteState
                    onLongClick(actionsLabel) { menu = true; true }
                    customActions = buildList {
                        add(CustomAccessibilityAction(favoriteLabel) { onFavorite(); true })
                        add(CustomAccessibilityAction(editLabel) { onEdit(); true })
                        add(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                        onMoveEarlier?.let { add(CustomAccessibilityAction(moveEarlierLabel) { it(); true }) }
                        onMoveLater?.let { add(CustomAccessibilityAction(moveLaterLabel) { it(); true }) }
                    }
                }
    ) {
        Row(
            Modifier.graphicsLayer {
                translationX = dragOffset.x; translationY = dragOffset.y
                scaleX = if (dragging) 1.04f else 1f; scaleY = scaleX
            }.clip(RoundedCornerShape(24.dp)).background(background)
                .border(if (dropTarget) 2.dp else 0.dp, if (dropTarget) foreground else Color.Transparent, RoundedCornerShape(24.dp))
                .indication(interactions, ripple())
                .heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            cn.super12138.todo.ui.components.TagArtwork(name, Modifier.size(28.dp).clearAndSetSemantics {})
            if (favorite) Icon(painterResource(R.drawable.ic_star), null, Modifier.size(16.dp), tint = foreground)
            Text(name, color = foreground, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {})
            Text(number, color = foreground, style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), modifier = Modifier.clearAndSetSemantics {})
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(favoriteLabel) },
                leadingIcon = { Icon(painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline), null) },
                onClick = { menu = false; onFavorite() })
            DropdownMenuItem(text = { Text(editLabel) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_edit_task), null) },
                onClick = { menu = false; onEdit() })
            HorizontalDivider()
                DropdownMenuItem(text = { Text(moveEarlierLabel) }, enabled = onMoveEarlier != null,
                    onClick = { menu = false; onMoveEarlier?.invoke() })
                DropdownMenuItem(text = { Text(moveLaterLabel) }, enabled = onMoveLater != null,
                    onClick = { menu = false; onMoveLater?.invoke() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(deleteLabel) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error),
                onClick = { menu = false; onDelete() })
        }
    }
}
