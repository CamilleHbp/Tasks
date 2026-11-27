package cn.super12138.todo.ui.pages.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import cn.super12138.todo.ui.components.DueTimeDialog
import cn.super12138.todo.logic.formatDueTime
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cn.super12138.todo.R
import cn.super12138.todo.logic.model.SortingMethod
import cn.super12138.todo.ui.components.TopAppBarScaffold
import cn.super12138.todo.ui.pages.settings.components.SettingsCategory
import cn.super12138.todo.ui.pages.settings.components.SettingsContainer
import cn.super12138.todo.ui.pages.settings.components.SettingsItem
import cn.super12138.todo.ui.pages.settings.components.SettingsPlainBox
import cn.super12138.todo.ui.pages.settings.components.SettingsRadioDialog
import cn.super12138.todo.ui.pages.settings.components.SettingsRadioOptions
import cn.super12138.todo.ui.pages.settings.components.SwitchSettingsItem
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun SettingsInterface(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsInterfaceInteractionViewModel = koinViewModel()
) {
    val uiState by viewModel.interfaceUiState.collectAsStateWithLifecycle()
    val rolloverDefault by viewModel.rolloverDefault.collectAsStateWithLifecycle()
    val defaultDueTime by viewModel.defaultDueTime.collectAsStateWithLifecycle()
    val scheduling by viewModel.schedulingPreferences.collectAsStateWithLifecycle()
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    var schedulingDialog by rememberSaveable { mutableStateOf(0) }
    val weekOptions = listOf(SettingsRadioOptions(0, stringResource(R.string.scheduling_follow_system))) +
        java.time.DayOfWeek.entries.map { SettingsRadioOptions(it.value,
            it.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale)) }
    val timeOptions = listOf(SettingsRadioOptions(0, stringResource(R.string.scheduling_clock)),
        SettingsRadioOptions(1, stringResource(R.string.scheduling_keyboard)))
    val dateOptions = listOf(SettingsRadioOptions(0, stringResource(R.string.scheduling_calendar)),
        SettingsRadioOptions(1, stringResource(R.string.scheduling_keyboard)))
    var showDueTime by rememberSaveable { mutableStateOf(false) }

    val sortingList = SortingMethod.entries.map {
        SettingsRadioOptions(
            id = it.id,
            text = stringResource(it.nameRes)
        )
    }

    TopAppBarScaffold(
        title = stringResource(R.string.pref_interface_interaction),
        onBack = onNavigateUp,
        modifier = modifier,
    ) {
        SettingsContainer(Modifier.fillMaxSize()) {
            item {
                SettingsCategory(
                    title = stringResource(R.string.pref_category_todo_list),
                    first = true
                )
                /*SwitchSettingsItem(
                    leadingIconRes = R.drawable.ic_checklist,
                    title = stringResource(R.string.pref_show_completed),
                    description = stringResource(R.string.pref_show_completed_desc),
                    checked = showCompleted,
                    onCheckedChange = { scope.launch { DataStoreManager.setShowCompleted(it) } }
                )*/
                SettingsItem(
                    leadingIconRes = R.drawable.ic_sort,
                    title = stringResource(R.string.pref_sorting_method),
                    description = stringResource(uiState.sortingMethod.nameRes),
                    onClick = { viewModel.showSortingMethodDialog() }
                )
            }

            item {
                SwitchSettingsItem(
                    leadingIconRes = R.drawable.ic_eye_tracking,
                    title = stringResource(R.string.pref_text_field_auto_focus),
                    description = stringResource(R.string.pref_text_field_auto_focus_desc),
                    checked = uiState.textFieldAutoFocus,
                    onCheckedChange = { viewModel.setTextFieldAutoFocus(it) }
                )
            }

            item {
                SettingsCategory(stringResource(R.string.scheduling_title))
                SettingsItem(leadingIconRes = R.drawable.ic_clock,
                    title = stringResource(R.string.scheduling_time_picker), titleMaxLines = Int.MAX_VALUE,
                    description = timeOptions[if (scheduling.timePickerDial) 0 else 1].text,
                    onClick = { schedulingDialog = 1 })
            }
            item {
                SettingsItem(leadingIconRes = R.drawable.ic_agenda,
                    title = stringResource(R.string.scheduling_date_picker), titleMaxLines = Int.MAX_VALUE,
                    description = dateOptions[if (scheduling.datePickerCalendar) 0 else 1].text,
                    onClick = { schedulingDialog = 2 })
            }
            item {
                SettingsItem(leadingIconRes = R.drawable.ic_agenda,
                    title = stringResource(R.string.scheduling_week_start), titleMaxLines = Int.MAX_VALUE,
                    description = weekOptions.first { it.id == scheduling.firstDayOfWeek }.text,
                    onClick = { schedulingDialog = 3 })
                SettingsPlainBox(stringResource(R.string.scheduling_week_start_hint))
            }
            item {
                SettingsItem(
                    leadingIconRes = R.drawable.ic_pending,
                    title = stringResource(R.string.pref_default_due_time),
                    description = formatDueTime(defaultDueTime),
                    onClick = { showDueTime = true }
                )
            }

            item {
                SwitchSettingsItem(leadingIconRes = R.drawable.ic_rollover,
                    title = stringResource(R.string.rollover_default),
                    description = stringResource(R.string.rollover_default_hint),
                    checked = rolloverDefault, onCheckedChange = viewModel::setRolloverDefault)
            }
            item {
                SettingsCategory(stringResource(R.string.pref_category_global))
                SwitchSettingsItem(
                    checked = uiState.secureMode,
                    leadingIconRes = R.drawable.ic_shield,
                    title = stringResource(R.string.pref_secure_mode),
                    description = stringResource(R.string.pref_secure_mode_desc),
                    onCheckedChange = { viewModel.setSecureMode(it) }
                )
            }

            item {
                SwitchSettingsItem(
                    checked = uiState.hapticFeedback,
                    leadingIconRes = R.drawable.ic_touch_long,
                    title = stringResource(R.string.pref_haptic_feedback),
                    description = stringResource(R.string.pref_haptic_feedback_desc),
                    onCheckedChange = { viewModel.setHapticFeedback(it) }
                )
                SettingsPlainBox(stringResource(R.string.pref_haptic_feedback_more_info))
            }
        }

        if (showDueTime) DueTimeDialog(defaultDueTime, initialDial = scheduling.timePickerDial,
            onConfirm = { viewModel.setDefaultDueTime(it); showDueTime = false },
            onDismiss = { showDueTime = false })

        if (schedulingDialog != 0) {
            val options = when (schedulingDialog) { 1 -> timeOptions; 2 -> dateOptions; else -> weekOptions }
            val current = when (schedulingDialog) {
                1 -> if (scheduling.timePickerDial) 0 else 1
                2 -> if (scheduling.datePickerCalendar) 0 else 1
                else -> scheduling.firstDayOfWeek
            }
            SettingsRadioDialog(visible = true,
                title = stringResource(when (schedulingDialog) {
                    1 -> R.string.scheduling_time_picker
                    2 -> R.string.scheduling_date_picker
                    else -> R.string.scheduling_week_start
                }), currentOptions = options.first { it.id == current }, options = options,
                onSelect = {
                    when (schedulingDialog) {
                        1 -> viewModel.setTimePickerDial(it == 0)
                        2 -> viewModel.setDatePickerCalendar(it == 0)
                        else -> viewModel.setFirstDayOfWeek(it)
                    }
                    schedulingDialog = 0
                }, onDismiss = { schedulingDialog = 0 })
        }
        SettingsRadioDialog(
            visible = uiState.showSortingMethodDialog,
            title = stringResource(R.string.pref_sorting_method),
            currentOptions = SettingsRadioOptions(
                id = uiState.sortingMethod.id,
                text = stringResource(uiState.sortingMethod.nameRes)
            ),
            options = sortingList,
            onSelect = { viewModel.setSortingMethod(it) },
            onDismiss = { viewModel.hideSortingMethodDialog() }
        )
    }
}
