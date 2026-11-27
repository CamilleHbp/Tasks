package studio.camille.vervetwodo.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale
import java.util.ResourceBundle

class TagColorPickerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun wheelDragBrightnessAndHexStayInSync() {
        var color by mutableStateOf("#FFFFFF")
        val strings = ResourceBundle.getBundle("messages", Locale.FRENCH)
        compose.setContent { MaterialTheme { TagColorPicker(color, { strings.getString(it) }) { color = it } } }
        compose.onNodeWithContentDescription("Disque chromatique").performTouchInput {
            down(center)
            moveTo(androidx.compose.ui.geometry.Offset(width.toFloat() - 1f, center.y))
            up()
        }
        compose.runOnIdle {
            val rgb = color.drop(1).toInt(16)
            assertEquals(255, rgb shr 16)
            org.junit.Assert.assertTrue((rgb and 255) < 5)
        }
        compose.onNodeWithContentDescription("Luminosité").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle { assertEquals("#000000", color) }
        compose.onNodeWithContentDescription("Luminosité").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        compose.runOnIdle { assertEquals(255, color.drop(1).toInt(16) shr 16) }
        compose.onNode(hasSetTextAction()).performTextReplacement("#00FF00")
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Luminosité").performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        compose.runOnIdle { assertEquals("#008000", color) }
        compose.onNode(hasSetTextAction()).performTextReplacement("invalid")
        compose.onNodeWithContentDescription("Disque chromatique").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals("#808080", color) }
    }
}
