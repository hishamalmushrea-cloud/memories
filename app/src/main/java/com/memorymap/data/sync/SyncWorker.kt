package com.memorymap.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.memorymap.data.remote.SupabaseClientProvider
import com.memorymap.domain.model.SyncOutcome
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.LOCAL_USER_ID
import com.memorymap.domain.repository.SyncRepository
import com.memorymap.util.MmLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Drains the offline queue.
 *
 * Three states are all treated as success rather than failure, because none of
 * them is something the user did wrong or can fix by retrying: no Supabase
 * project configured, no session restored yet, and the local offline account.
 *
 * A run that did try and failed returns [androidx.work.ListenableWorker.Result.retry],
 * which backs off and tries again. The rows themselves are already marked
 * `SYNC_ERROR`, so a retry picks up exactly the rows that did not make it.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val supabase: SupabaseClientProvider,
    private val auth: AuthRepository,
    private val sync: SyncRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!supabase.isAvailable) {
            // Offline-only install. This is a supported state, not a failure.
            MmLog.d("Sync skipped: no Supabase project configured")
            return Result.success()
        }

        // The worker can outlive the process that started it, so the session is
        // restored here rather than assumed to be in memory.
        auth.restoreSession()
        val userId = auth.currentUserId.value
        if (userId.isNullOrBlank() || userId == LOCAL_USER_ID) {
            MmLog.d("Sync skipped: no cloud account is signed in")
            return Result.success()
        }

        val state = sync.syncNow(userId)
        MmLog.d("Sync finished with outcome ${state.lastOutcome}")
        return if (state.lastOutcome == SyncOutcome.ERROR) Result.retry() else Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "memorymap-sync"
    }
}

/** Enqueues synchronisation. The periodic run is scheduled once at app start. */
object SyncScheduler {

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkOnly())
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Runs one sync immediately, for the button on the profile screen. */
    fun syncNow(context: Context) {
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(networkOnly()).build(),
        )
    }

    private fun networkOnly(): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
