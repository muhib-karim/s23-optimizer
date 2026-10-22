package dev.muhib.s23optimizer.platform

import android.content.Context
import androidx.work.*
import dev.muhib.s23optimizer.OptimizerApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Optional hourly battery level sample for the drain panel. Reads ACTION_BATTERY_CHANGED only; no shell. */
class BatterySampleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as OptimizerApplication).graph
        if (!graph.preferences.getBoolean(PREF, false)) return Result.success()
        return try {
            graph.repository.save(graph.audit.batterySample())
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.failure() }
    }
    companion object {
        const val PREF = "battery_samples"
        fun schedule(context: Context, enabled: Boolean) {
            val manager = WorkManager.getInstance(context)
            if (!enabled) manager.cancelUniqueWork("battery-samples")
            else manager.enqueueUniquePeriodicWork("battery-samples", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BatterySampleWorker>(1, TimeUnit.HOURS).build())
        }
    }
}
