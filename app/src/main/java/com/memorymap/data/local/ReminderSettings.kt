package com.memorymap.data.local

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the one persistent fact about the daily reminder lives: is it on?
 *
 * A port rather than a direct `SharedPreferences` read, the same boundary the
 * lock uses, so the repository that schedules around it can be tested with an
 * in-memory fake and no Android.
 */
interface ReminderSettings {
    /** Whether the user has turned the daily reminder on. Survives a restart. */
    var enabled: Boolean
}

/**
 * The Android implementation: one boolean in a private preferences file.
 *
 * Not a secret and not the encrypted store - it says whether a local reminder is
 * on, which is the user's own choice and nothing more. No time, no location and
 * no content ever passes through here; the reminder is computed on the device at
 * the moment it fires and never leaves it.
 */
@Singleton
class SharedPreferencesReminderSettings @Inject constructor(
    @param:ApplicationContext context: Context,
) : ReminderSettings {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit { putBoolean(KEY_ENABLED, value) }
        }

    private companion object {
        const val FILE_NAME = "memorymap_reminder"
        const val KEY_ENABLED = "reminder_enabled"
    }
}
