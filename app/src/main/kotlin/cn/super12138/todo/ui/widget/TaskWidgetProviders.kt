package cn.super12138.todo.ui.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import cn.super12138.todo.ui.widget.all.AllIncompleteWidget
import cn.super12138.todo.ui.widget.all.AllIncompleteWidgetReceiver
import cn.super12138.todo.ui.widget.today.TodayTaskWidget
import cn.super12138.todo.ui.widget.today.TodayTaskWidgetReceiver
import cn.super12138.todo.ui.widget.upcoming.UpcomingTaskWidget
import cn.super12138.todo.ui.widget.upcoming.UpcomingTaskWidgetReceiver

internal val taskWidgetProviders: Map<Class<out GlanceAppWidgetReceiver>, () -> GlanceAppWidget> =
    mapOf(
        AllIncompleteWidgetReceiver::class.java to ::AllIncompleteWidget,
        TodayTaskWidgetReceiver::class.java to ::TodayTaskWidget,
        UpcomingTaskWidgetReceiver::class.java to ::UpcomingTaskWidget
    )
