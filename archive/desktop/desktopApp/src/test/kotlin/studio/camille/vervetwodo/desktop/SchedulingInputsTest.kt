package studio.camille.vervetwodo.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.ResourceBundle
import java.util.TimeZone
import javax.imageio.ImageIO

class SchedulingInputsTest {
    @get:Rule val compose = createComposeRule()
    private fun strings(language: String): Texts {
        val bundle = ResourceBundle.getBundle("messages", Locale.forLanguageTag(language))
        return { bundle.getString(it) }
    }
    private fun capture(name: String) {
        val file = File("build/input-review/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", file)
    }

    @Test fun calendarUsesUtcDateWithoutTimezoneShift() {
        val original = TimeZone.getDefault()
        try {
            listOf("Pacific/Honolulu", "Pacific/Kiritimati", "Europe/Paris").forEach { zone ->
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                listOf("2028-02-29", "2026-03-29", "2026-10-25").forEach { date ->
                    assertEquals(date, calendarDate(calendarMillis(date)!!))
                }
            }
        } finally { TimeZone.setDefault(original) }
        assertNull(calendarMillis("2026-02-30"))
    }

    @Test fun calendarSelectionCancelReopenAndClear() {
        var date by mutableStateOf("2028-02-28")
        compose.setContent { MaterialTheme { Box(Modifier.width(360.dp)) { DateInput(date, strings("en")) { date = it } } } }
        compose.onNodeWithTag("date-picker-open").performClick()
        compose.onNodeWithText("Tuesday, February 29, 2028").performClick()
        capture("calendar-english-light")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals("2028-02-28", date) }
        compose.onNodeWithTag("date-picker-open").performClick()
        compose.onNodeWithText("Tuesday, February 29, 2028").performClick()
        compose.onNodeWithText("Apply").performClick()
        compose.runOnIdle { assertEquals("2028-02-29", date) }
        compose.onNodeWithText("Clear").performClick()
        compose.runOnIdle { assertEquals("", date) }
    }

    @Test fun frenchCalendarAndTimeInputs() {
        val original = Locale.getDefault()
        Locale.setDefault(Locale.FRENCH)
        try {
            var date by mutableStateOf("2026-10-23")
            var time by mutableStateOf("09:00")
            compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
                Surface { Column(Modifier.width(360.dp).padding(12.dp)) {
                    DateInput(date, strings("fr")) { date = it }
                    TimeInput(time, strings("fr"), "Rappel", optional = false) { time = it }
                } }
            } }
            compose.onNodeWithText("Heure").performTextReplacement("12")
            compose.onNodeWithText("Minute").performTextReplacement("45")
            compose.runOnIdle { assertEquals("12:45", time) }
            compose.onNodeWithText("Heure").performTextReplacement("25")
            compose.runOnIdle { assertEquals("12:45", time) }
            compose.onNodeWithTag("date-picker-open").performClick()
            capture("calendar-french-dark")
            compose.onNodeWithText("Annuler").performClick()
            capture("time-french-dark")
        } finally { Locale.setDefault(original) }
    }

    @Test fun optionalTimeAndSearchableZone() {
        var time by mutableStateOf("")
        var zone by mutableStateOf("UTC")
        compose.setContent { MaterialTheme { Column(Modifier.width(360.dp)) {
            TimeInput(time, strings("en"), "Include a time") { time = it }
            TimeZoneInput(zone, strings("en"), true) { zone = it }
        } } }
        compose.onNodeWithContentDescription("Include a time").performClick()
        compose.onNodeWithText("Hour").assertExists()
        compose.onNodeWithContentDescription("Include a time").performClick()
        compose.runOnIdle { assertEquals("", time) }
        compose.onNodeWithText(strings("en")("timeZone") + ": UTC").performClick()
        compose.onNodeWithText("Search city or time zone").performTextInput("paris")
        compose.onNodeWithText("Europe/Paris").performClick()
        compose.runOnIdle { assertEquals("Europe/Paris", zone) }
    }

    @Test fun fullTimeFieldIsOvertypedByTypedDigits() {
        // Caret at end of "09": typing 1 then 0 must give 10 instead of being rejected.
        val one = timePartEdit("09", TextFieldValue("091", TextRange(3)), 0..23)
        assertEquals("1", one)
        assertEquals("10", timePartEdit(one!!, TextFieldValue("10", TextRange(2)), 0..23))
        // Caret at start of "09".
        assertEquals("1", timePartEdit("09", TextFieldValue("109", TextRange(1)), 0..23))
        // Out-of-range pair keeps only the newly typed digit.
        assertEquals("5", timePartEdit("2", TextFieldValue("25", TextRange(2)), 0..23))
        assertEquals("", timePartEdit("0", TextFieldValue("", TextRange(0)), 0..23))
        assertNull(timePartEdit("09", TextFieldValue("0a", TextRange(2)), 0..23))
    }

    @Test fun emptyTimePartsUseDefaultReminderTime() {
        var time by mutableStateOf("")
        compose.setContent { MaterialTheme { CompositionLocalProvider(LocalDefaultReminderTime provides "18:30") {
            Column(Modifier.width(360.dp)) { TimeInput(time, strings("en"), "Include a time") { time = it } }
        } } }
        compose.onNodeWithContentDescription("Include a time").performClick()
        compose.runOnIdle { assertEquals("18:30", time) }
        compose.onNodeWithText("Hour").performTextReplacement("10")
        compose.runOnIdle { assertEquals("10:30", time) }
        compose.onNodeWithText("Hour").performTextReplacement("")
        compose.onNodeWithText("Minute").performTextReplacement("")
        compose.runOnIdle { assertEquals("18:30", time) }
    }
}
