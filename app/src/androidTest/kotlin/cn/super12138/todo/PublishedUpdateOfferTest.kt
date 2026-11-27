package cn.super12138.todo

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import cn.super12138.todo.ui.activities.MainActivity
import org.junit.Test
import java.io.File

/** Run explicitly on the preceding APK after publishing, with -e expectedUpdateVersion. */
class PublishedUpdateOfferTest {
    @Test fun previousBuildOffersPublishedUpdate() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val version = InstrumentationRegistry.getArguments().getString("expectedUpdateVersion") ?: return
        val context = instrumentation.targetContext
        context.getSharedPreferences("camille-updater", 0).edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use {
            val message = "Version $version is available. Download and install it now?"
            val deadline = System.currentTimeMillis() + 30_000
            while (true) {
                try {
                    onView(withText(message)).check(matches(isDisplayed()))
                    break
                } catch (error: Throwable) {
                    if (System.currentTimeMillis() >= deadline) throw error
                    Thread.sleep(250)
                }
            }
            val file = File(context.getExternalFilesDir(null), "update-review/automatic-offer.png")
            file.parentFile!!.mkdirs()
            file.outputStream().use { output ->
                instrumentation.uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, output)
            }
        }
    }
}
