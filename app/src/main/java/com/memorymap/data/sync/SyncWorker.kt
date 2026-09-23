package com.memorymap.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.memorymap.data.remote.SupabaseClientProvider
import com.memorymap.util.MmLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Drains the offline queue. Phase 1 wires the trigger, the network constraint
 * and the "cloud not configured" guard; the per-table upload with retry and
 * conflict handling lands in Phase 6.
 *
 * Because the work is periodic and network-constrained, a record written on a
 * plane reaches the server by itself once a connection comes back.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val supabase: SupabaseClientProvider,
) : Worker(context, params) {

    override fun doWork(): Result {
        if (!supabase.isAvailable) {
            // Offline-only install. This is a supported state, not a failure.
            MmLog.d("Sync skipped: no Supabase project configured")
            return Result.success()
        }
        MmLog.d("Sync tick (upload pipeline is implemented in Phase 6)")
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "memorymap-sync"
    }
}

/** Enqueues the periodic sync. Called once from [com.memorymap.MemoryMapApp]. */
object SyncScheduler {

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
