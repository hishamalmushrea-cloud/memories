package com.memorymap.data.repository

import android.content.Context
import com.memorymap.data.local.ReminderSettings
import com.memorymap.data.reminder.ReminderScheduler
import com.memorymap.domain.repository.ReminderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the reminder choice and keeps the scheduled work in step with it.
 *
 * The setting and the schedule are changed together, never one without the
 * other: a reminder left scheduled after the user turned it off is the app doing
 * something behind their back, which is the one thing this project promises not
 * to do. Turning it on schedules the daily work; turning it off cancels it.
 */
@Singleton
class ReminderRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: ReminderSettings,
) : ReminderRepository {

    private val _isEnabled = MutableStateFlow(settings.enabled)
    override val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    override suspend fun setEnabled(enabled: Boolean) {
        settings.enabled = enabled
        _isEnabled.value = enabled
        if (enabled) ReminderScheduler.schedule(context) else ReminderScheduler.cancel(context)
    }
}
