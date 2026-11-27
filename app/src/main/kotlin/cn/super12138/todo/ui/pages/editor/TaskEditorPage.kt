package cn.super12138.todo.ui.pages.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import cn.super12138.todo.R
import cn.super12138.todo.constants.Constants
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.ui.components.ConfirmDialog
import cn.super12138.todo.ui.pages.editor.components.*
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SharedTransitionScope.TaskAddPage(
    modifier: Modifier = Modifier,
    onNavigateUp: () -> Unit,
    initialTag: String = ""
) = TaskEditorPage(
    task = null,
    modifier = modifier
        .sharedBounds(
            sharedContentState = rememberSharedContentState(key = Constants.KEY_TODO_FAB_TRANSITION),
            animatedVisibilityScope = LocalNavAnimatedContentScope.current,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
        )
        .skipToLookaheadSize(), // 这个修饰符必须放后面
    onNavigateUp = onNavigateUp,
    initialCategory = initialTag
)

@Composable
fun SharedTransitionScope.TaskEditPage(
    modifier: Modifier = Modifier,
    task: TaskEntity,
    onNavigateUp: () -> Unit
) = TaskEditorPage(
    task = task,
    modifier = modifier
        .sharedBounds(
            sharedContentState = rememberSharedContentState(key = "${Constants.KEY_TODO_ITEM_TRANSITION}_${task.id}"),
            animatedVisibilityScope = LocalNavAnimatedContentScope.current,
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds
        )
        .skipToLookaheadSize(),
    onNavigateUp = onNavigateUp
)

@Composable
fun TaskEditorPage(
    modifier: Modifier = Modifier,
    task: TaskEntity? = null,
    onNavigateUp: () -> Unit = {},
    onSaved: () -> Unit = onNavigateUp,
    quickAdd: Boolean = false,
    initialCategory: String = "",
    initialDueDateMillis: Long? = null,
    viewModel: EditorViewModel = koinViewModel { parametersOf(task) }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val composerFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val saveError = stringResource(R.string.error_task_save)
    val tagOrderError = stringResource(R.string.tag_change_failed)
    val undoLabel = stringResource(R.string.widget_undo)
    val removedLabel = stringResource(R.string.editor_subtask_removed)
    val pastedLabel = stringResource(R.string.editor_subtasks_added)
    val saveLabel = stringResource(R.string.action_save)
    var validate by rememberSaveable { mutableStateOf(false) }
    var moreExpanded by remember { mutableStateOf(false) }
    var notesVisible by rememberSaveable { mutableStateOf(uiState.details.isNotBlank()) }
    var notesExpanded by rememberSaveable { mutableStateOf(false) }

    fun navigateUpIfUnchanged() {
        if (uiState.isSaving) return
        if (viewModel.isModified()) viewModel.showExitConfirmDialog() else onNavigateUp()
    }

    fun saveTask() {
        validate = true
        if (uiState.content.isBlank()) {
            scope.launch {
                listState.scrollToItem(0)
                focusRequester.requestFocus()
                keyboard?.show()
            }
        } else viewModel.saveNewTask()
    }

    fun addSteps(text: String = uiState.subtaskDraft, pastedList: Boolean = false) {
        val added = viewModel.addSubtasks(text, pastedList)
        composerFocus.requestFocus()
        keyboard?.show()
        if (added.size > 1) scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            if (snackbarHostState.showSnackbar(pastedLabel, undoLabel,
                    withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                viewModel.undoAddedSubtasks(added.map { it.id }.toSet())
            }
        }
    }

    LaunchedEffect(quickAdd, initialCategory, initialDueDateMillis) {
        viewModel.setCreationDefaults(initialCategory, initialDueDateMillis)
    }
    LaunchedEffect(quickAdd || uiState.shouldAutoFocusContent) {
        if (quickAdd || uiState.shouldAutoFocusContent) focusRequester.requestFocus()
    }
    LaunchedEffect(uiState.isSaved) {
        if (uiState.isSaved) {
            if (task != null && !task.isCompleted && uiState.isCompleted) viewModel.setConfettiVisibility(true)
            onSaved()
        }
    }
    LaunchedEffect(uiState.saveFailed) {
        if (uiState.saveFailed) snackbarHostState.showSnackbar(saveError)
    }
    LaunchedEffect(viewModel, tagOrderError) {
        viewModel.tagOrderErrors.collect { snackbarHostState.showSnackbar(tagOrderError) }
    }
    BackHandler(onBack = ::navigateUpIfUnchanged)

    Scaffold(
        modifier = modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (task == null) R.string.action_add_task else R.string.title_edit_task)) },
                navigationIcon = {
                    IconButton(onClick = ::navigateUpIfUnchanged, enabled = !uiState.isSaving) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (task != null) Box {
                        IconButton(onClick = { moreExpanded = true }, enabled = !uiState.isSaving) {
                            Icon(painterResource(R.drawable.ic_more), stringResource(R.string.view_more))
                        }
                        DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                leadingIcon = { Icon(painterResource(R.drawable.ic_delete), null) },
                                onClick = { moreExpanded = false; viewModel.showDeleteConfirmDialog() }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding(), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Button(
                            enabled = !uiState.isSaving,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = saveLabel },
                            onClick = { saveTask() }
                        ) { Text(if (uiState.isSaving) stringResource(R.string.action_saving) else saveLabel, textAlign = TextAlign.Center) }
                    }
                }
            }
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = 20.dp)
            ) {
                item(key = "title") {
                    TextField(
                        value = uiState.content, onValueChange = viewModel::setContentText,
                        label = { Text(stringResource(R.string.placeholder_add_todo)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        textStyle = MaterialTheme.typography.headlineSmall,
                        minLines = 1, maxLines = 6, enabled = !uiState.isSaving,
                        isError = validate && uiState.content.isBlank(),
                        supportingText = if (validate && uiState.content.isBlank()) {{
                            Text(stringResource(R.string.error_no_content_entered))
                        }} else null,
                        colors = editorTextFieldColors(),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                    )
                }
                item(key = "notes") {
                    if (notesVisible || uiState.details.isNotBlank()) {
                        TextField(
                            value = uiState.details, onValueChange = viewModel::setDetailsText,
                            label = { Text(stringResource(R.string.task_details)) },
                            supportingText = { Text(stringResource(R.string.task_details_markdown_hint)) },
                            minLines = 1, maxLines = if (notesExpanded) Int.MAX_VALUE else 3,
                            enabled = !uiState.isSaving, colors = editorTextFieldColors(),
                            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) notesExpanded = true }
                        )
                        if (uiState.details.length > 140 || uiState.details.count { it == '\n' } > 2) {
                            TextButton(onClick = { notesExpanded = !notesExpanded }) {
                                Text(stringResource(if (notesExpanded) R.string.editor_collapse_details else R.string.editor_expand_details))
                            }
                        }
                    } else {
                        TextButton(onClick = { notesVisible = true }, enabled = !uiState.isSaving) {
                            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(20.dp))
                            Text(stringResource(R.string.editor_add_details), Modifier.padding(start = 8.dp))
                        }
                    }
                }
                item(key = "subtasks-heading") {
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.task_subtasks), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium)
                        if (uiState.subtasks.isNotEmpty()) Text(
                            stringResource(R.string.task_subtasks_progress, uiState.subtasks.count { it.isCompleted }, uiState.subtasks.size),
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                itemsIndexed(uiState.subtasks, key = { _, item -> item.id }) { index, item ->
                    EditorSubtaskRow(item, index, enabled = !uiState.isSaving,
                        onChange = { viewModel.updateSubtask(item.id, it) },
                        onCompleted = { viewModel.setSubtaskCompleted(item.id, it) },
                        onNext = {
                            scope.launch {
                                listState.scrollToItem(3 + uiState.subtasks.size)
                                withFrameNanos { }
                                composerFocus.requestFocus()
                                keyboard?.show()
                            }
                        },
                        onRemove = {
                            viewModel.removeSubtask(item.id)
                            scope.launch {
                                snackbarHostState.currentSnackbarData?.dismiss()
                                if (snackbarHostState.showSnackbar(removedLabel, undoLabel,
                                        withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                                    viewModel.restoreSubtask(item, index)
                                }
                            }
                        })
                }
                item(key = "subtask-composer") {
                    SubtaskComposer(
                        value = uiState.subtaskDraft,
                        onValueChange = {
                            if (uiState.subtaskDraft.isEmpty() && it.length > 1 && (it.contains('\n') || it.contains('\r'))) addSteps(it, pastedList = true)
                            else viewModel.setSubtaskDraft(it)
                        },
                        onAdd = { addSteps() }, enabled = !uiState.isSaving,
                        focusRequester = composerFocus, itemCount = uiState.subtasks.size
                    )
                }
                item(key = "schedule") {
                    Column(Modifier.padding(top = 12.dp)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(Modifier.height(20.dp))
                        EditorSchedule(uiState.dueDateMillis, uiState.dueTimeMinutes,
                            uiState.defaultDueTimeMinutes, uiState.recurrence, viewModel::setDueDate,
                            viewModel::setSchedule, viewModel::setRecurrence, !uiState.isSaving, uiState.schedulingPreferences, uiState.rollover, viewModel::setRollover)
                    }
                }
                item(key = "tags") {
                    EditorTags(uiState.categoryList, uiState.tags, uiState.tagColors,
                        onToggle = { if (it in uiState.tags) viewModel.removeTag(it) else viewModel.addTag(it) },
                        onAdd = { viewModel.addTag(it) }, enabled = !uiState.isSaving,
                        onReorder = if (task != null) viewModel::reorderTags else null)
                }
                item(key = "priority") {
                    Box(Modifier.padding(top = 12.dp)) {
                        EditorPrioritySlider(uiState.priority, viewModel::setPriority, !uiState.isSaving)
                    }
                }
            }
        }
    }
    ConfirmDialog(
        visible = uiState.showExitConfirmDialog, iconRes = R.drawable.ic_undo,
        text = stringResource(R.string.tip_discard_changes),
        onConfirm = { viewModel.hideExitConfirmDialog(); onNavigateUp() },
        onDismiss = viewModel::hideExitConfirmDialog
    )
    ConfirmDialog(
        visible = uiState.showDeleteConfirmDialog, iconRes = R.drawable.ic_delete,
        text = stringResource(R.string.tip_delete_task, 1),
        onConfirm = { viewModel.deleteTask(); onNavigateUp() },
        onDismiss = viewModel::hideDeleteConfirmDialog
    )
}
