package com.memorymap.data.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.memorymap.MainActivity
import com.memorymap.R
import com.memorymap.data.local.ReminderSettings
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.domain.repository.LOCAL_USER_ID
import com.memorymap.domain.repository.OnThisDayRepository
import com.memorymap.domain.usecase.ReminderContent
import com.memorymap.util.MmLog
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Fires the daily reminder, if the user turned it on and there is something worth saying.
 *
 * Everything happens on the device: the content is read from the local database at
 * the moment it fires, decided by [ReminderContent], and posted as a notification.
 * No network, no server, nothing leaves the phone - which is what lets a background
 * worker coexist with an app that promises it does nothing behind the user's back.
 * The only thing that runs is the thing the user switched on.
 *
 * A day with nothing to resurface and an entry already written produces no
 * notification at all, because a reminder that fires every day regardless is noise
 * the user learns to ignore.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val settings: ReminderSettings,
    private val auth: AuthRepository,
    private val onThisDay: OnThisDayRepository,
    private val diary: DiaryRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!settings.enabled) {
            // Turned off since this was scheduled; say nothing and let the cancel catch up.
            return Result.success()
        }
        // The worker can outlive the process that scheduled it, so the session is
        // restored here rather than assumed to be in memory. An offline install has
        // no session and falls back to the local account, which is exactly right:
        // the reminder works with no connection at all.
        auth.restoreSession()
        val userId = auth.currentUserId.value ?: LOCAL_USER_ID
        val today = LocalDate.now()

        val decision = ReminderContent.decide(
            onThisDayCount = onThisDay.items(userId, today).size,
            wroteToday = diary.getDiaryNote(userId, today) != null,
        )
        when (decision) {
            ReminderContent.Decision.Nothing -> MmLog.d("Reminder: nothing worth saying today")
            is ReminderContent.Decision.OnThisDay -> notify(
                applicationContext.getString(R.string.reminder_notify_on_this_day, decision.count),
            )
            ReminderContent.Decision.WriteToday -> notify(
                applicationContext.getString(R.string.reminder_notify_write),
            )
        }
        return Result.success()
    }

    private fun notify(message: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        // Creating the channel again is a no-op, so this is safe on every run.
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.reminder_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val open = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(applicationContext.getString(R.string.reminder_notify_title))
            .setContentText(message)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // On Android 13+ this is silently dropped if the user never granted the
        // notification permission, which is the correct outcome, not a failure.
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val UNIQUE_NAME = "memorymap-reminder"
        private const val CHANNEL_ID = "memorymap-reminder"
        private const val NOTIFICATION_ID = 1001
    }
}

/** Enqueues or cancels the daily reminder. Driven only by the user's switch. */
object ReminderScheduler {

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            ReminderWorker.UNIQUE_NAME,
            // UPDATE rather than KEEP, so turning the reminder off and back on
            // restarts the daily clock instead of leaving the old one running.
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(ReminderWorker.UNIQUE_NAME)
    }
}
