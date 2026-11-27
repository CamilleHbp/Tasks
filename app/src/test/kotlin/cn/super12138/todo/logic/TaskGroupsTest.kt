package cn.super12138.todo.logic

import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TaskGroupsTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 9, 22)
    private fun task(id: Int, day: LocalDate? = today, tags: List<String> = emptyList(), completed: Boolean = false,
        time: Int? = null, priority: Float = 0f) = TaskEntity("Task $id", "", completed, priority,
        day?.atStartOfDay(zone)?.toInstant()?.toEpochMilli(), id, createdAtMillis = id.toLong(),
        tags = tags, dueTimeMinutes = time)
    private fun groups(tasks: List<TaskEntity>, config: TaskViewConfig = TaskViewConfig(), date: LocalDate = today) =
        taskGroups(tasks, config, date, zone, DayOfWeek.MONDAY)

    @Test fun everyAgendaTaskHasOneHomeAcrossWeekBoundaries() {
        val tasks = (-3..20).mapIndexed { index, offset -> task(index + 1, today.plusDays(offset.toLong())) } + task(30, null)
        for (days in 1..7) for (thisWeek in listOf(false, true)) for (nextWeek in listOf(false, true)) {
            val result = groups(tasks, TaskViewConfig(individualDays = days, thisWeek = thisWeek, nextWeek = nextWeek))
            assertEquals(tasks.map { it.id }.sorted(), result.flatMap { it.tasks }.map { it.id }.sorted())
        }
    }

    @Test fun nextWeekIsWholeCalendarWeekNotRollingSevenDays() {
        val sunday = task(1, LocalDate.of(2026, 10, 4))
        val mondayAfter = task(2, LocalDate.of(2026, 10, 5))
        val result = groups(listOf(sunday, mondayAfter))
        assertEquals(listOf(1), result.single { it.kind == TaskGroupKind.NextWeek }.tasks.map { it.id })
        assertEquals(listOf(2), result.single { it.kind == TaskGroupKind.Later }.tasks.map { it.id })
    }

    @Test fun sevenIndividualDaysDoNotDuplicateNextWeek() {
        val friday = LocalDate.of(2026, 9, 25)
        val tasks = (0..9).map { task(it + 1, friday.plusDays(it.toLong())) }
        val result = groups(tasks, TaskViewConfig(individualDays = 7), friday)
        assertFalse(result.any { it.kind == TaskGroupKind.ThisWeek })
        assertEquals(LocalDate.of(2026, 10, 2), result.single { it.kind == TaskGroupKind.NextWeek }.date)
        assertEquals(tasks.size, result.sumOf { it.tasks.size })
    }

    @Test fun localeWeekStartChangesCalendarBoundary() {
        val sunday = task(1, LocalDate.of(2026, 9, 27))
        val mondayStart = groups(listOf(sunday))
        val sundayStart = taskGroups(listOf(sunday), TaskViewConfig(), today, zone, DayOfWeek.SUNDAY)
        assertTrue(mondayStart.any { it.kind == TaskGroupKind.ThisWeek && sunday in it.tasks })
        assertTrue(sundayStart.any { it.kind == TaskGroupKind.NextWeek && sunday in it.tasks })
    }

    @Test fun priorityAndCreationKeepTheSameDateGroups() {
        val tasks = listOf(task(1, priority = -1f), task(2, priority = 2f), task(3, today.plusDays(1)))
        val due = groups(tasks)
        for (sort in TaskGroupSort.entries) assertEquals(due.map { it.key }, groups(tasks, TaskViewConfig(sort = sort)).map { it.key })
        assertEquals(listOf(2, 1), groups(tasks, TaskViewConfig(sort = TaskGroupSort.Priority)).first { it.kind == TaskGroupKind.Day }.tasks.map { it.id })
    }

    @Test fun dueTimePrecedesPriorityAndUndatedStaysLastWhenReversed() {
        val list = listOf(task(1, time = null, priority = 2f), task(2, time = 900), task(3, time = 600), task(4, null))
        assertEquals(listOf(3, 2, 1, 4), list.sortedWith(taskComparator(TaskGroupSort.DueDate, zone)).map { it.id })
        assertEquals(4, list.sortedWith(taskComparator(TaskGroupSort.DueDateLatest, zone)).last().id)
    }

    @Test fun completedPastTasksNeverAppearOverdueAndRemainFindable() {
        val list = listOf(task(1, today.minusDays(1)), task(2, today.minusDays(1), completed = true), task(3, null, completed = true))
        val all = groups(list, TaskViewConfig(status = TaskStatusFilter.All))
        assertEquals(listOf(1), all.single { it.kind == TaskGroupKind.Overdue }.tasks.map { it.id })
        assertEquals(listOf(2), all.single { it.kind == TaskGroupKind.Earlier }.tasks.map { it.id })
        assertEquals(setOf(2, 3), groups(list, TaskViewConfig(status = TaskStatusFilter.Completed)).flatMap { it.tasks }.map { it.id }.toSet())
    }

    @Test fun tagsRepeatIdentityButSearchDeduplicatesAndUntaggedExists() {
        val list = listOf(task(1, tags = listOf("Home", "Work")), task(2))
        val config = TaskViewConfig(mode = TaskViewMode.Tags)
        assertEquals(listOf("Home", "Work", null), groups(list, config).map { it.tag })
        assertEquals(3, groups(list, config).sumOf { it.tasks.size })
        assertEquals(listOf(1), taskGroups(list, config, today, zone, query = "Task 1").flatMap { it.tasks }.map { it.id })
    }

    @Test fun selectedTagsAreOrFilterAndDoNotExposeUnselectedGroups() {
        val list = listOf(task(1, tags = listOf("Home", "Work")), task(2, tags = listOf("Work")), task(3))
        val config = TaskViewConfig(mode = TaskViewMode.Tags, tags = setOf("Home", ""))
        val result = groups(list, config)
        assertEquals(listOf(TaskGroupKind.Tag, TaskGroupKind.Untagged), result.map { it.kind })
        assertEquals(listOf(1, 3), result.flatMap { it.tasks }.map { it.id })
    }

    @Test fun settingsRoundTripAndMalformedSettingsHaveSafeDefaults() {
        val config = TaskViewConfig(mode = TaskViewMode.Tags, tags = setOf("Home"), tagOrder = listOf("Work", "Home"),
            collapsed = setOf("tag:Home"), status = TaskStatusFilter.Completed, emphasizeGroups = true)
        assertEquals(config, TaskViewConfig.decode(config.encode()))
        assertEquals(7, TaskViewConfig.decode(TaskViewConfig(individualDays = 99).encode()).individualDays)
        assertEquals(TaskViewConfig(), TaskViewConfig.decode("broken"))
    }

    @Test fun daylightSavingTransitionDoesNotShiftDailyMembership() {
        val date = LocalDate.of(2026, 10, 25)
        val tasks = listOf(task(1, date), task(2, date.plusDays(1)))
        assertEquals(listOf(date, date.plusDays(1)), groups(tasks, date = date).filter { it.tasks.isNotEmpty() }.map { it.date })
    }
}

