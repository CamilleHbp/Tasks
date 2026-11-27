package cn.super12138.todo

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import cn.super12138.todo.di.VerveDoDI
import cn.super12138.todo.ui.pages.crash.CrashHandler
import cn.super12138.todo.utils.updateTaskWidgets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class VerveDoApp : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@VerveDoApp)
            modules(VerveDoDI.allModules)
        }

        get<cn.super12138.todo.logic.sync.SyncRepository>().start()
        cn.super12138.todo.logic.sync.SyncWorker.schedule(this)

        cn.super12138.todo.logic.RolloverWorker.schedule(this)
        applicationScope.launch {
            while (true) {
                try { get<cn.super12138.todo.logic.TaskRepository>().rollForward() }
                catch (exception: CancellationException) { throw exception }
                catch (exception: Exception) { Log.w("TaskRollover", "Could not roll tasks forward", exception) }
                kotlinx.coroutines.delay(30_000)
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            get<cn.super12138.todo.logic.TaskRepository>().getAllTasks().collect {
                try { cn.super12138.todo.logic.reminders.TaskReminders.reconcile(this@VerveDoApp) }
                catch (error: Exception) { if (error is CancellationException) throw error; Log.e("TaskReminders", "Scheduling failed", error) }
            }
        }

        val crashHandler = CrashHandler(applicationContext)
        Thread.setDefaultUncaughtExceptionHandler(crashHandler)

        applicationScope.launch {
            get<cn.super12138.todo.logic.SettingsRepository>().appearanceFlow
                .distinctUntilChanged().collect { refreshTaskWidgets() }
        }
        applicationScope.launch {
            get<cn.super12138.todo.logic.SettingsRepository>().schedulingPreferencesFlow
                .map { it.firstDayOfWeek }.distinctUntilChanged().collect { refreshTaskWidgets() }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applicationScope.launch {
            runCatching { updateTaskWidgets(this@VerveDoApp) }
                .onFailure { Log.w("WidgetLanguage", "Could not refresh widget language", it) }
        }
    }

    suspend fun refreshTaskWidgets() {
        // Enqueue the Glance updates before returning from a save. The application's
        // scope keeps this work alive if the editor or its ViewModel is destroyed.
        applicationScope.launch(Dispatchers.IO) {
            try {
                updateTaskWidgets(this@VerveDoApp)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                // A widget failure must not turn a committed task into a failed save.
                Log.w("TaskWidgets", "Could not refresh task widgets", exception)
            }
        }.join()
    }
}
