package dev.muhib.s23optimizer.platform

import android.content.Context
import androidx.work.*
import dev.muhib.s23optimizer.OptimizerApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

class DailyAuditWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as OptimizerApplication).graph
        if (!graph.preferences.getBoolean("daily_audit", false)) return Result.success()
        return try {
            // Diagnostics only. Local collectors still run when Shizuku is stopped after reboot.
            graph.repository.save(graph.audit.collect("DAILY"))
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (runAttemptCount < 2) Result.retry() else Result.failure() }
    }
    companion object {
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) manager.cancelUniqueWork("daily-audit")
            else manager.enqueueUniquePeriodicWork("daily-audit", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DailyAuditWorker>(24, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build())
        }
    }
}
