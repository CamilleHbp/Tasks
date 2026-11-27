package cn.super12138.todo.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import cn.super12138.todo.R

enum class VerveDoDestinations(
    val route: NavKey,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
    @param:DrawableRes val selectedIcon: Int
) {
    Agenda(
        route = VerveDoScreen.Overview,
        label = R.string.view_agenda,
        icon = R.drawable.ic_agenda,
        selectedIcon = R.drawable.ic_agenda
    ),
    Tags(
        route = VerveDoScreen.Tasks,
        label = R.string.view_tags,
        icon = R.drawable.ic_tags,
        selectedIcon = R.drawable.ic_tags
    )
}
