package cn.super12138.todo

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.ui.components.CategoryIconPicker
import cn.super12138.todo.ui.components.categoryDrawable
import cn.super12138.todo.ui.components.categoryLabel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import studio.camille.vervetwodo.integration.CategoryIcons

class SharedIconsCatalogueTest {
    @get:Rule val compose = createComposeRule()
    @Test fun sharedFamiliesAreAvailableAndSelectable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(SharedCategoryIcons.ids, CategoryIcons.ids)
        for (id in CategoryIcons.ids) {
            assertNotNull(context.getDrawable(categoryDrawable(id)))
            assertTrue(context.getString(categoryLabel(id)).isNotBlank())
        }
        var selected by mutableStateOf("tag")
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { CategoryIconPicker(selected, true) { selected = it } } } }
        for (id in listOf("cinema", "vegetables", "breakfast")) {
            compose.onNodeWithContentDescription(context.getString(categoryLabel(id))).performScrollTo().performClick()
            compose.runOnIdle { assertEquals(id, selected) }
        }
        assertEquals("tag", CategoryIcons.displayId("unknown-future-id"))
    }
}
