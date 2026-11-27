package cn.super12138.todo.logic

import org.junit.Assert.*
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale

class SchedulingPreferencesTest {
    @Test fun defaultsUseClockCalendarAndLocaleWeekStart() {
        val preferences = SchedulingPreferences()
        assertTrue(preferences.timePickerDial)
        assertTrue(preferences.datePickerCalendar)
        assertEquals(DayOfWeek.SUNDAY, preferences.weekStart(Locale.US))
        assertEquals(DayOfWeek.MONDAY, preferences.weekStart(Locale.FRANCE))
    }
    @Test fun nextWeekUsesChosenBoundaryIncludingMonthAndYearRollover() {
        val monday = SchedulingPreferences(firstDayOfWeek = 1)
        assertEquals(LocalDate.of(2026, 10, 5), monday.nextWeek(LocalDate.of(2026, 10, 3), Locale.US))
        assertEquals(LocalDate.of(2026, 10, 12), monday.nextWeek(LocalDate.of(2026, 10, 5), Locale.US))
        assertEquals(LocalDate.of(2027, 1, 4), monday.nextWeek(LocalDate.of(2026, 12, 31), Locale.US))
        val sunday = SchedulingPreferences(firstDayOfWeek = 7)
        assertEquals(LocalDate.of(2027, 1, 3), sunday.nextWeek(LocalDate.of(2026, 12, 31), Locale.FRANCE))
    }
    @Test fun everyWeekStartSelectsAStrictlyFutureDayWithinAWeek() {
        for (first in 1..7) for (offset in 0L..13L) {
            val date = LocalDate.of(2026, 12, 25).plusDays(offset)
            val next = SchedulingPreferences(firstDayOfWeek = first).nextWeek(date, Locale.US)
            assertEquals(first, next.dayOfWeek.value)
            assertTrue(next > date && next <= date.plusDays(7))
        }
    }
}
