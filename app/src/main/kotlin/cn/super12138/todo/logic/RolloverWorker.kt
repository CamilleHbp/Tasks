package cn.super12138.todo.logic

import android.content.Context
import androidx.work.*
import org.koin.core.context.GlobalContext
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

class RolloverWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        GlobalContext.get().get<TaskRepository>().rollForward()
        Result.success()
    } catch (exception: CancellationException) { throw exception }
      catch (_: Exception) { Result.retry() }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("task-rollover",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RolloverWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}
