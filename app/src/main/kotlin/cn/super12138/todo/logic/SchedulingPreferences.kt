package cn.super12138.todo.logic

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

data class SchedulingPreferences(
    val timePickerDial: Boolean = true,
    val datePickerCalendar: Boolean = true,
    /** ISO weekday 1–7, or 0 to follow the app locale. */
    val firstDayOfWeek: Int = 0
) {
    fun weekStart(locale: Locale): DayOfWeek = if (firstDayOfWeek in 1..7)
        DayOfWeek.of(firstDayOfWeek) else WeekFields.of(locale).firstDayOfWeek

    fun nextWeek(today: LocalDate, locale: Locale): LocalDate =
        today.with(TemporalAdjusters.next(weekStart(locale)))

    fun calendarLocale(locale: Locale): Locale = if (firstDayOfWeek !in 1..7) locale else
        Locale.Builder().setLocale(locale).setUnicodeLocaleKeyword("fw",
            weekStart(locale).name.lowercase(Locale.ROOT).take(3)).build()
}
