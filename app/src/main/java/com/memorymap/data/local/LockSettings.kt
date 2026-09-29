package com.memorymap.data.local

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the one persistent fact about the lock lives: is it on?
 *
 * A port rather than a direct `SharedPreferences` read so the state machine in
 * [com.memorymap.data.repository.AppLockRepositoryImpl] can be tested with an
 * in-memory fake on the JVM, with no Robolectric and no Android - the same
 * boundary the app draws around [com.memorymap.data.remote.MediaStorage].
 */
interface LockSettings {
    /** Whether the user has turned the app lock on. Survives a restart. */
    var enabled: Boolean
}

/**
 * The Android implementation: one boolean in a private preferences file.
 *
 * Not the encrypted store the session uses, on purpose - this is not a secret.
 * It says whether the lock is on, which an attacker holding the device can
 * discover in one tap of the settings screen anyway; the credential that opens
 * the app is the platform's and never comes near this file.
 */
@Singleton
class SharedPreferencesLockSettings @Inject constructor(
    @param:ApplicationContext context: Context,
) : LockSettings {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            // The androidx extension commits on the way out, so the write cannot
            // be forgotten at the end of a chain.
            prefs.edit { putBoolean(KEY_ENABLED, value) }
        }

    private companion object {
        const val FILE_NAME = "memorymap_lock"
        const val KEY_ENABLED = "lock_enabled"
    }
}
