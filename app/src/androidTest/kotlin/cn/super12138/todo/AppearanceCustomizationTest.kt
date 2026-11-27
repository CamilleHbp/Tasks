package cn.super12138.todo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cn.super12138.todo.logic.model.AppearanceSettings
import cn.super12138.todo.ui.pages.settings.components.appearance.AccentColorPicker
import cn.super12138.todo.ui.pages.settings.components.appearance.WidgetTransparencyPicker
import cn.super12138.todo.ui.theme.VerveDoTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AppearanceCustomizationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun customColorRejectsInvalidHexAndCancelPreservesSelection() {
        var selected: Int? = null
        compose.setContent {
            VerveDoTheme {
                AccentColorPicker(AppearanceSettings()) { selected = it }
            }
        }
        compose.onNodeWithText("Custom colour").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("#12GG00")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("#123456")
        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(null, selected) }
        compose.onNodeWithText("Custom colour").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("#123456")
        compose.onNodeWithText("Save").performClick()
        compose.runOnIdle { assertEquals(0xFF123456.toInt(), selected) }
    }

    @Test fun transparencySupportsAccessibilityAndReset() {
        var saved = -1
        compose.setContent {
            var percent by remember { mutableStateOf(0) }
            VerveDoTheme {
                WidgetTransparencyPicker(percent) { percent = it; saved = it }
            }
        }
        compose.onNodeWithContentDescription("Widget transparency")
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(75f) }
        compose.onNodeWithText("75% transparent").assertExists()
        compose.runOnIdle { assertEquals(75, saved) }
        compose.onNodeWithText("Reset transparency").performClick()
        compose.runOnIdle { assertEquals(0, saved) }
    }
}
