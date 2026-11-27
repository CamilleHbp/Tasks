package cn.super12138.todo.logic.sync

import android.content.Context
import androidx.work.*
import org.koin.core.context.GlobalContext

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (GlobalContext.get().get<SyncRepository>().synchronize()) Result.success() else Result.retry()

    companion object {
        fun schedule(context: Context) {
            // Retire the old polling job. Local revisions, live streams and network
            // callbacks drive synchronization; WorkManager must not reintroduce it.
            WorkManager.getInstance(context).cancelUniqueWork("vervetwodo-sync")
        }
    }
}
