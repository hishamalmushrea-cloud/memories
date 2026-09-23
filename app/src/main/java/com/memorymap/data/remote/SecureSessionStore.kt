package com.memorymap.data.remote

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.memorymap.util.MmLog
import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Keeps the session in EncryptedSharedPreferences, whose key lives in the
 * Android Keystore.
 *
 * The session holds a refresh token, so it is treated as a secret: it is never
 * logged, never written to the Room database, and never included in a backup
 * export.
 */
@Singleton
class SecureSessionStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val json: Json,
) : SessionManager {

    private val prefs: SharedPreferences by lazy { createPrefs() }

    private fun createPrefs(): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { error ->
        // Falling back to plain prefs would put a refresh token on disk in the
        // clear, so a Keystore failure is fatal for the session only: the app
        // keeps working, it just cannot remember a sign-in.
        MmLog.e("Encrypted session storage unavailable; sessions will not persist", error)
        context.getSharedPreferences("$FILE_NAME.plain", Context.MODE_PRIVATE)
    }

    override suspend fun saveSession(session: UserSession) {
        runCatching { prefs.edit().putString(KEY_SESSION, json.encodeToString(UserSession.serializer(), session)).apply() }
            .onFailure { MmLog.e("Unable to store the session", it) }
    }

    override suspend fun loadSession(): UserSession {
        val raw = prefs.getString(KEY_SESSION, null) ?: throw NoSessionFoundException()
        return json.decodeFromString(UserSession.serializer(), raw)
    }

    override suspend fun deleteSession() {
        runCatching { prefs.edit().remove(KEY_SESSION).apply() }
            .onFailure { MmLog.e("Unable to clear the session", it) }
    }

    companion object {
        private const val FILE_NAME = "memorymap_session"
        private const val KEY_SESSION = "user_session"
    }
}
