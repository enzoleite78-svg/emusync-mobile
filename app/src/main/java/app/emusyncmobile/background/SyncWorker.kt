package app.emusyncmobile.background

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.emusyncmobile.AppServices
import app.emusyncmobile.auth.NotAuthorizedException
import java.util.concurrent.TimeUnit

/**
 * Sync automático em segundo plano. O Android roda isso no máximo a cada 15 minutos
 * (o sistema pode atrasar para economizar bateria).
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val services = AppServices(applicationContext)
        if (!services.store.load().autoSyncEnabled) return Result.success()

        return try {
            val remote = services.engine.connect()
            val result = services.engine.autoSync(remote)
            Log.i(TAG, "Sync automático: enviados/baixados=${result.synced}, conflitos=${result.conflicts}, falhas=${result.failures}")
            Notifications.showResult(applicationContext, result)
            Result.success()
        } catch (e: NotAuthorizedException) {
            Notifications.showLoginNeeded(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Sync automático falhou", e)
            // Provavelmente sem internet ou erro temporário do Drive: tenta de novo mais tarde
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "EmuSyncWorker"
        private const val WORK_NAME = "emusync-auto-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
