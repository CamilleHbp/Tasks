package cn.super12138.todo.ui.pages.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtaskEntryTest {
    @Test fun multilinePasteTrimsBlankLinesAndListMarkers() {
        assertEquals(listOf("First step", "Second step", "Third step", "Fourth step"),
            subtaskLines("  - First step\r\n\n• Second step\n1. Third step\n2) Fourth step  "))
    }

    @Test fun ordinaryPunctuationAndRepeatedStepsArePreserved() {
        assertEquals(listOf("-5 degrees", "1.5 litres", "Repeat", "Repeat"),
            subtaskLines("-5 degrees\n1.5 litres\nRepeat\nRepeat"))
        assertEquals(emptyList<String>(), subtaskLines(" \r\n \t"))
    }
}
