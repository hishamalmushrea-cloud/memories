package com.memorymap.data.repository

import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.entities.UserEntity
import com.memorymap.data.remote.SupabaseClientProvider
import com.memorymap.domain.model.AuthState
import com.memorymap.domain.model.User
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.LOCAL_USER_ID
import com.memorymap.util.MmLog
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Supabase-backed authentication with an offline account as the fallback.
 *
 * Rules this class enforces:
 *  - No password is ever written to the local database. Only the user id, email
 *    and display name are stored, and the token stays in the Keystore.
 *  - A stored session is restored on start, so a signed-in user keeps working
 *    with no connection.
 *  - When no project is configured the app still has an account: the local one.
 */
@Singleton
class SupabaseAuthRepository @Inject constructor(
    private val supabaseProvider: SupabaseClientProvider,
    private val database: MemoryMapDatabase,
) : AuthRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Unknown)
    override val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _currentUserId = MutableStateFlow<String?>(null)
    override val currentUserId: StateFlow<String?> = _currentUserId.asStateFlow()

    override val isCloudConfigured: Boolean get() = supabaseProvider.isAvailable

    init {
        val client = supabaseProvider.get()
        if (client != null) {
            scope.launch {
                client.auth.sessionStatus.collect { status ->
                    when (status) {
                        is SessionStatus.Authenticated -> onAuthenticated(status.session.user?.id, status.session.user?.email)
                        is SessionStatus.NotAuthenticated -> onSignedOut()
                        // Initializing and refresh failures keep the current state;
                        // a refresh problem must not log the user out locally.
                        else -> Unit
                    }
                }
            }
        }
    }

    override suspend fun restoreSession() {
        val client = supabaseProvider.get()
        if (client == null) {
            // No cloud project: fall back to whatever local account exists.
            val local = database.userDao().getById(LOCAL_USER_ID)
            if (local != null) {
                publish(local.toDomainUser(), offlineAccount = true)
            } else {
                _authState.value = AuthState.SignedOut
            }
            return
        }

        // The SDK loads the stored session during installation; wait for it so
        // the first frame does not show the sign-in screen by mistake.
        runCatching { client.auth.awaitInitialization() }
            .onFailure { MmLog.w("Session restoration did not finish in time", it) }

        val session = client.auth.currentSessionOrNull()
        if (session != null) {
            onAuthenticated(session.user?.id, session.user?.email)
        } else {
            val local = database.userDao().getById(LOCAL_USER_ID)
            if (local != null) publish(local.toDomainUser(), offlineAccount = true) else _authState.value = AuthState.SignedOut
        }
    }

    override suspend fun signUp(email: String, password: String, displayName: String): AuthRepository.Result {
        val client = supabaseProvider.get()
            ?: return localSignUp(email, displayName)

        return runCatching {
            client.auth.signUpWith(Email) {
                this.email = email.trim()
                this.password = password
            }
            // The session arrives through sessionStatus; make sure the local
            // profile row exists even if the callback has not run yet.
            val user = client.auth.currentUserOrNull()
            rememberUser(user?.id, user?.email ?: email.trim(), displayName)
            AuthRepository.Result.Success as AuthRepository.Result
        }.getOrElse { error ->
            MmLog.e("Sign up failed", error)
            AuthRepository.Result.Failure(messageKeyFor(error))
        }
    }

    override suspend fun signIn(email: String, password: String): AuthRepository.Result {
        val client = supabaseProvider.get()
            ?: return localSignIn(email)

        return runCatching {
            client.auth.signInWith(Email) {
                this.email = email.trim()
                this.password = password
            }
            val user = client.auth.currentUserOrNull()
            rememberUser(user?.id, user?.email ?: email.trim(), user?.email ?: email.trim())
            AuthRepository.Result.Success as AuthRepository.Result
        }.getOrElse { error ->
            MmLog.e("Sign in failed", error)
            AuthRepository.Result.Failure(messageKeyFor(error))
        }
    }

    override suspend fun resetPassword(email: String): AuthRepository.Result {
        val client = supabaseProvider.get()
            ?: return AuthRepository.Result.Failure("auth_error_offline")

        return runCatching {
            client.auth.resetPasswordForEmail(email.trim())
            AuthRepository.Result.Success as AuthRepository.Result
        }.getOrElse { error ->
            MmLog.e("Password reset failed", error)
            AuthRepository.Result.Failure(messageKeyFor(error))
        }
    }

    override suspend fun signOut() {
        runCatching { supabaseProvider.get()?.auth?.signOut() }
            .onFailure { MmLog.w("Sign out could not reach the server", it) }
        // The local profile row stays: the archive belongs to the user and must
        // survive a sign-out. Only the session is dropped.
        onSignedOut()
    }

    override suspend fun continueOffline(displayName: String?): User {
        val name = displayName?.takeIf { it.isNotBlank() } ?: OFFLINE_DISPLAY_NAME
        val existing = database.userDao().getById(LOCAL_USER_ID)
        val user = User(
            id = LOCAL_USER_ID,
            email = existing?.email ?: OFFLINE_EMAIL,
            displayName = name,
            avatarUrl = existing?.avatarUrl,
            createdAt = existing?.createdAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: LocalDateTime.now(),
        )
        database.userDao().upsert(user.toEntityRow())
        publish(user, offlineAccount = true)
        return user
    }

    // --- internals ---

    private suspend fun onAuthenticated(userId: String?, email: String?) {
        if (userId.isNullOrBlank()) return
        rememberUser(userId, email ?: "", email ?: "")
    }

    private suspend fun rememberUser(userId: String?, email: String, displayName: String) {
        if (userId.isNullOrBlank()) return
        val existing = database.userDao().getById(userId)
        val user = User(
            id = userId,
            email = email.ifBlank { existing?.email.orEmpty() },
            displayName = displayName.ifBlank { existing?.displayName ?: email },
            avatarUrl = existing?.avatarUrl,
            createdAt = existing?.createdAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: LocalDateTime.now(),
        )
        database.userDao().upsert(user.toEntityRow())
        publish(user, offlineAccount = false)
    }

    private fun publish(user: User, offlineAccount: Boolean) {
        _currentUserId.value = user.id
        _authState.value = AuthState.SignedIn(user = user, offlineAccount = offlineAccount)
    }

    private fun onSignedOut() {
        _currentUserId.value = null
        _authState.value = AuthState.SignedOut
    }

    /**
     * Local sign-up. There is no password check because there is no password:
     * the offline account is a device-local identity, and pretending to verify a
     * credential would be a lie.
     */
    private suspend fun localSignUp(email: String, displayName: String): AuthRepository.Result {
        val user = User(
            id = LOCAL_USER_ID,
            email = email.trim(),
            displayName = displayName.ifBlank { email.trim() },
        )
        database.userDao().upsert(user.toEntityRow())
        publish(user, offlineAccount = true)
        return AuthRepository.Result.Success
    }

    private suspend fun localSignIn(email: String): AuthRepository.Result {
        val stored = database.userDao().getById(LOCAL_USER_ID)
        return if (stored != null && stored.email.equals(email.trim(), ignoreCase = true)) {
            publish(stored.toDomainUser(), offlineAccount = true)
            AuthRepository.Result.Success
        } else {
            AuthRepository.Result.Failure("auth_error_offline")
        }
    }

    /**
     * Maps a failure to a stable key the UI resolves through strings.xml, so no
     * server error text (which can contain the email) reaches the screen.
     */
    /** Visible for tests: maps a server failure to a stable strings.xml key. */
    internal fun messageKeyFor(error: Throwable): String {
        val text = error.message.orEmpty().lowercase()
        return when {
            "invalid login credentials" in text || "invalid credentials" in text -> "auth_error_credentials"
            "weak password" in text || "password should be at least" in text -> "auth_error_weak_password"
            "already registered" in text || "already been registered" in text -> "auth_error_email_taken"
            "email not confirmed" in text -> "auth_error_email_unconfirmed"
            "rate limit" in text -> "auth_error_rate_limited"
            else -> "auth_error_generic"
        }
    }

    private fun UserEntity.toDomainUser(): User = User(
        id = id,
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        createdAt = runCatching { LocalDateTime.parse(createdAt) }.getOrDefault(LocalDateTime.now()),
    )

    private fun User.toEntityRow(): UserEntity = UserEntity(
        id = id,
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        createdAt = createdAt.toString(),
    )

    private companion object {
        const val OFFLINE_DISPLAY_NAME = "حساب محلي"
        const val OFFLINE_EMAIL = "offline@local"
    }
}
