package cn.super12138.todo.ui.navigation

import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import cn.super12138.todo.ui.pages.editor.TaskAddPage
import cn.super12138.todo.ui.pages.editor.TaskEditPage
import cn.super12138.todo.ui.pages.overview.OverviewPage
import cn.super12138.todo.ui.pages.settings.SettingsAbout
import cn.super12138.todo.ui.pages.settings.SettingsAboutLicence
import cn.super12138.todo.ui.pages.settings.SettingsAppearance
import cn.super12138.todo.ui.pages.settings.SettingsAppearanceDarkMode
import cn.super12138.todo.ui.pages.settings.SettingsData
import cn.super12138.todo.ui.pages.settings.SettingsDeveloperOptions
import cn.super12138.todo.ui.pages.settings.SettingsDeveloperOptionsPadding
import cn.super12138.todo.ui.pages.settings.SettingsInterface
import cn.super12138.todo.ui.pages.settings.SettingsMain
import cn.super12138.todo.ui.pages.tasks.TasksPage
import cn.super12138.todo.ui.theme.fadeScale
import cn.super12138.todo.ui.theme.materialSharedAxisX
import cn.super12138.todo.ui.theme.veilFade

@OptIn(ExperimentalAnimationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TopNavigation(
    modifier: Modifier = Modifier,
    backStack: TopLevelBackStack<NavKey>
) {
    fun onBack() {
        backStack.removeLast()
    }

    val veilColor = MaterialTheme.colorScheme.surfaceDim
    fun editorTransition() = NavDisplay.transitionSpec {
        veilFade(veilColor)
    } + NavDisplay.popTransitionSpec {
        veilFade(veilColor)
    } + NavDisplay.predictivePopTransitionSpec {
        veilFade(veilColor)
    }

    val initialOffestFactor = 0.10f
    fun settingsTransition() = NavDisplay.transitionSpec {
        materialSharedAxisX(
            initialOffsetX = { (it * initialOffestFactor).toInt() },
            targetOffsetX = { -(it * initialOffestFactor).toInt() }
        )
    } + NavDisplay.popTransitionSpec {
        materialSharedAxisX(
            initialOffsetX = { -(it * initialOffestFactor).toInt() },
            targetOffsetX = { (it * initialOffestFactor).toInt() }
        )
    } + NavDisplay.predictivePopTransitionSpec {
        materialSharedAxisX(
            initialOffsetX = { -(it * initialOffestFactor).toInt() },
            targetOffsetX = { (it * initialOffestFactor).toInt() }
        )
    }

    val defaultTransition = fadeScale(
        effectSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec()
    )

    SharedTransitionLayout {
        NavDisplay(
            backStack = backStack.backStack,
            onBack = ::onBack,
            transitionSpec = { defaultTransition },
            popTransitionSpec = { defaultTransition },
            predictivePopTransitionSpec = { defaultTransition },
            entryDecorators = listOf(
                // 让ViewModel Owner绑定在每一个页面上而非Activity上
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator()
            ),
            entryProvider = entryProvider {
                entry<VerveDoScreen.Overview> {
                    cn.super12138.todo.ui.pages.collections.TaskCollectionPage(
                        mode = cn.super12138.todo.logic.model.TaskViewMode.Agenda,
                        toAdd = { backStack.add(VerveDoScreen.Editor.Add()) },
                        toDetail = { backStack.add(VerveDoScreen.TaskDetail(it.id)) },
                        toEdit = { backStack.add(VerveDoScreen.Editor.Edit(it)) },
                        toSettings = { backStack.add(VerveDoScreen.Settings.Main) }
                    )
                }
                entry<VerveDoScreen.Tasks> {
                    cn.super12138.todo.ui.pages.collections.TagCloudPage(
                        toTasks = { tag ->
                            backStack.add(VerveDoScreen.TaskCollection(
                                cn.super12138.todo.logic.model.TaskViewConfig(
                                    mode = cn.super12138.todo.logic.model.TaskViewMode.Tags,
                                    status = cn.super12138.todo.logic.model.TaskStatusFilter.All,
                                    tags = setOf(tag)
                                ).encode(), tagScope = tag
                            ))
                        },
                        toSettings = { backStack.add(VerveDoScreen.Settings.Main) }
                    )
                }
                entry<VerveDoScreen.TaskCollection> { args ->
                    cn.super12138.todo.ui.pages.collections.TaskCollectionPage(
                        mode = cn.super12138.todo.logic.model.TaskViewConfig.decode(args.configuration).mode,
                        initialConfiguration = args.configuration,
                        tagScope = args.tagScope,
                        toAdjacentTag = if (args.tagScope != null) ({ tag ->
                            backStack.replaceTop(VerveDoScreen.TaskCollection(
                                cn.super12138.todo.logic.model.TaskViewConfig.decode(args.configuration).copy(tags = setOf(tag)).encode(), tag))
                        }) else null,
                        onBack = ::onBack,
                        toAdd = { backStack.add(VerveDoScreen.Editor.Add(initialTag = args.tagScope.orEmpty())) },
                        toDetail = { backStack.add(VerveDoScreen.TaskDetail(it.id)) },
                        toEdit = { backStack.add(VerveDoScreen.Editor.Edit(it)) },
                        toSettings = { backStack.add(VerveDoScreen.Settings.Main) }
                    )
                }

                entry<VerveDoScreen.Editor.Add>(metadata = editorTransition()) { args ->
                    TaskAddPage(onNavigateUp = ::onBack, initialTag = args.initialTag)
                }

                entry<VerveDoScreen.TaskDetail>(metadata = settingsTransition()) { args ->
                    cn.super12138.todo.ui.pages.detail.TaskDetailPage(
                        taskId = args.taskId,
                        onBack = ::onBack,
                        onEdit = { backStack.add(VerveDoScreen.Editor.Edit(it)) }
                    )
                }

                entry<VerveDoScreen.Editor.Edit>(metadata = editorTransition()) { editorArgs ->
                    TaskEditPage(
                        task = editorArgs.task,
                        onNavigateUp = ::onBack
                    )
                }

                entry<VerveDoScreen.Settings.Main> {
                    SettingsMain(
                        onBack = ::onBack,
                        toAppearancePage = { backStack.add(VerveDoScreen.Settings.Appearance) },
                        toAboutPage = { backStack.add(VerveDoScreen.Settings.About) },
                        toInterfacePage = { backStack.add(VerveDoScreen.Settings.Interface) },
                        toDataPage = { backStack.add(VerveDoScreen.Settings.Data) },
                    )
                }

                entry<VerveDoScreen.Settings.Appearance>(metadata = settingsTransition()) {
                    SettingsAppearance(
                        toDarkModePage = { backStack.add(VerveDoScreen.Settings.AppearanceDarkMode) },
                        onNavigateUp = ::onBack
                    )
                }

                entry<VerveDoScreen.Settings.AppearanceDarkMode>(metadata = settingsTransition()) {
                    SettingsAppearanceDarkMode(onNavigateUp = ::onBack)
                }

                entry<VerveDoScreen.Settings.Interface>(metadata = settingsTransition()) {
                    SettingsInterface(onNavigateUp = ::onBack)
                }

                entry<VerveDoScreen.Settings.Data>(metadata = settingsTransition()) {
                    SettingsData(
                        onNavigateUp = ::onBack
                    )
                }

                entry<VerveDoScreen.Settings.About>(metadata = settingsTransition()) {
                    SettingsAbout(
                        //toSpecialPage = { backStack.add(TodoScreen.Settings.AboutSpecial) },
                        toLicencePage = { backStack.add(VerveDoScreen.Settings.AboutLicence) },
                        toDevPage = { backStack.add(VerveDoScreen.Settings.DeveloperOptions) },
                        onNavigateUp = ::onBack,
                    )
                }

                /*entry<TodoScreen.Settings.AboutSpecial>(metadata = settingsTransition()) {
                    SettingsAboutSpecial(viewModel = viewModel)
                }*/

                entry<VerveDoScreen.Settings.AboutLicence>(metadata = settingsTransition()) {
                    SettingsAboutLicence(onNavigateUp = ::onBack)
                }

                entry<VerveDoScreen.Settings.DeveloperOptions>(metadata = settingsTransition()) {
                    SettingsDeveloperOptions(
                        toPaddingPage = { backStack.add(VerveDoScreen.Settings.DeveloperOptionsPadding) },
                        onNavigateUp = ::onBack
                    )
                }
                entry<VerveDoScreen.Settings.DeveloperOptionsPadding>(metadata = settingsTransition()) {
                    SettingsDeveloperOptionsPadding(onNavigateUp = ::onBack)
                }
            },
            modifier = modifier,
        )
    }
}
