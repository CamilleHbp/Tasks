package cn.super12138.todo

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.logic.TagRepository
import cn.super12138.todo.logic.database.TaskEntity
import cn.super12138.todo.logic.model.*
import cn.super12138.todo.ui.components.*
import cn.super12138.todo.ui.theme.VerveDoTheme
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.koin.core.context.GlobalContext
import java.io.File

class CategoryIconsUiTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun pickerPreservesSelectionAndRingCompletion() = runBlocking {
        val repository = GlobalContext.get().get<TagRepository>()
        val specs = listOf(Triple("Home",0xffb15b35.toInt(),"house"), Triple("Cinema",0xff945eb5.toInt(),"cinema"),
            Triple("Work",0xff3877b8.toInt(),"work"), Triple("Computer",0xff687b91.toInt(),"computer"))
        specs.forEach { (tag,color,icon) -> repository.save(tag.takeIf { it in repository.tags.first() },tag,color,icon) }
        var selected by mutableStateOf("house")
        var completed by mutableStateOf(false)
        val dark = InstrumentationRegistry.getArguments().getString("dark") == "true"
        val scale = if (dark) 1.3f else 1f
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                VerveDoTheme(darkTheme=dark,dynamicColor=false,animate=false) {
                    Surface { Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text("Agenda",style=MaterialTheme.typography.headlineSmall)
                        Row {
                            IconButton(onClick={completed=!completed}, modifier=Modifier.testTag("complete")) {
                                if(completed) Text("✓") else CategoryCompletionRing(specs.map { androidx.compose.ui.graphics.Color(it.second) })
                            }
                            Column {
                                Text("Review the kitchen drawings",style=MaterialTheme.typography.bodyLarge)
                                Text("10:30 · Checklist 1/3",style=MaterialTheme.typography.bodySmall)
                                CategoryIconStrip(specs.map { it.first }, onShowAll={}, repository=repository)
                            }
                        }
                        HorizontalDivider()
                        Row(horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                            CategoryCompletionRing(listOf(androidx.compose.ui.graphics.Color.White))
                            CategoryCompletionRing(listOf(androidx.compose.ui.graphics.Color.Black))
                            CategoryCompletionRing(listOf(MaterialTheme.colorScheme.surface))
                        }
                        CategoryIconPicker(selected,true) { selected=it }
                    } }
                }
            }
        }
        compose.onNodeWithContentDescription(instrumentation.targetContext.getString(R.string.category_icon_cinema)).performClick()
        compose.runOnIdle { assertEquals("cinema",selected) }
        compose.onNodeWithTag("complete").performClick()
        compose.runOnIdle { assertTrue(completed) }
        compose.onNodeWithTag("complete").performClick()
        compose.waitForIdle()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        val folder=File(instrumentation.targetContext.getExternalFilesDir(null),"category-icons-review").apply{mkdirs()}
        File(folder,if(dark) "dark-large.png" else "light.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        Unit
    }
}
