package cn.super12138.todo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.glance.appwidget.compose
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.logic.SettingsRepository
import cn.super12138.todo.logic.datastore.DataStoreManager
import cn.super12138.todo.logic.model.DarkMode
import cn.super12138.todo.ui.widget.all.AllIncompleteWidget
import cn.super12138.todo.ui.widget.today.TodayTaskWidget
import cn.super12138.todo.ui.widget.upcoming.UpcomingTaskWidget
import cn.super12138.todo.ui.widget.widgetThemeColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class WidgetAppearanceTest {
    @Test fun allWidgetsRenderTransparentBackgroundsAndOpaqueText() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val koin = GlobalContext.get()
        val settings = koin.get<SettingsRepository>()
        val store = koin.get<DataStoreManager>().dataStore
        val original = store.data.first()
        try {
            settings.setThemeColor(0xFF6750A4.toInt())
            settings.setDarkMode(DarkMode.Light.id)
            val density = context.resources.displayMetrics.density
            val width = (340 * density).toInt()
            val height = (420 * density).toInt()
            for (darkMode in listOf(DarkMode.Light, DarkMode.Dark)) {
                settings.setDarkMode(darkMode.id)
                val appearance = settings.appearanceFlow.first()
                val (light, dark) = widgetThemeColors(context, appearance)
                assertEquals(light.primary, dark.primary)
                assertEquals(light.surface, dark.surface)
                assertEquals(1f, light.onSurface.alpha)
                for (transparency in listOf(0, 50, 100)) {
                    settings.setWidgetTransparency(transparency)
                    for (widget in listOf(AllIncompleteWidget(), TodayTaskWidget(), UpcomingTaskWidget())) {
                        val remote = widget.compose(context, size = DpSize(340.dp, 420.dp))
                        instrumentation.runOnMainSync {
                            val view = remote.apply(context, FrameLayout(context))
                            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                            view.layout(0, 0, width, height)
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(bitmap))
                            val alpha = Color.alpha(bitmap.getPixel(width / 2, (2 * density).toInt()))
                            val expected = ((100 - transparency) / 100f * 255).toInt()
                            assertEquals("${widget.javaClass.simpleName} $darkMode $transparency", expected.toDouble(), alpha.toDouble(), 1.0)
                            // Text/actions must still be drawn at full opacity over an empty background.
                            if (transparency == 100) {
                                val pixels = IntArray(width * height)
                                bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                                assertTrue(pixels.any { Color.alpha(it) == 255 })
                            }
                            bitmap.recycle()
                        }
                    }
                }
            }
        } finally {
            store.edit { preferences ->
                preferences.clear()
                original.asMap().forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    preferences[key as androidx.datastore.preferences.core.Preferences.Key<Any>] = value
                }
            }
        }
    }
}
