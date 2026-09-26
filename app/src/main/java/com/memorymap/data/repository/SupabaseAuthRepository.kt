package com.memorymap.data.repository

import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.entities.UserEntity
import com.memorymap.data.remote.AccountApi
import com.memorymap.data.remote.ProfileRecord
import com.memorymap.data.remote.SupabaseClientProvider
import com.memorymap.domain.model.AuthState
import com.memorymap.domain.model.User
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.LOCAL_USER_ID
import com.memorymap.util.MmLog
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.postgrest
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
    private val accountApi: AccountApi,
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

        // Captured in locals: inside the Email config lambda an unqualified
        // `email`/`password` would resolve to the lambda receiver's own fields.
        val userEmail = email.trim()
        val userPassword = password
        return runCatching {
            client.auth.signUpWith(Email) {
                this.email = userEmail
                this.password = userPassword
            }
            // The session arrives through sessionStatus; make sure the local
            // profile row exists even if the callback has not run yet.
            val user = client.auth.currentUserOrNull()
            rememberUser(user?.id, user?.email ?: userEmail, displayName)
            AuthRepository.Result.Success as AuthRepository.Result
        }.getOrElse { error ->
            MmLog.e("Sign up failed", error)
            AuthRepository.Result.Failure(messageKeyFor(error))
        }
    }

    override suspend fun signIn(email: String, password: String): AuthRepository.Result {
        val client = supabaseProvider.get()
            ?: return localSignIn(email)

        val userEmail = email.trim()
        val userPassword = password
        return runCatching {
            client.auth.signInWith(Email) {
                this.email = userEmail
                this.password = userPassword
            }
            val user = client.auth.currentUserOrNull()
            rememberUser(user?.id, user?.email ?: userEmail, user?.email ?: userEmail)
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

    /**
     * Removes the account on the server, then the session on this device.
     *
     * The server does the whole job in one call - the records, the profile row
     * and the auth user - so there is no half-deleted state to report. That is
     * also why the order here is the opposite of a wipe: the server goes first,
     * and a failure stops everything after it. A device wiped before a failed
     * deletion would leave the user with nothing locally and an account still
     * holding their records, which is the one outcome nobody asked for.
     *
     * The session is then dropped locally rather than signed out over the
     * network, because there is no account left to sign out of.
     */
    override suspend fun deleteAccount(): AuthRepository.Deletion {
        // Not a network check: `isAvailable` only reads the build configuration.
        // Without a project there is no account on a server, so an unconfigured
        // build must say so instead of reporting a deletion it did not make.
        if (!supabaseProvider.isAvailable) return AuthRepository.Deletion.NOT_CONFIGURED

        return runCatching { accountApi.deleteAccount() }.fold(
            onSuccess = {
                // Best effort, and last: the account no longer exists, so a
                // session that cannot be cleared here and now is a stale token,
                // not data.
                runCatching { supabaseProvider.get()?.auth?.signOut(SignOutScope.LOCAL) }
                    .onFailure { MmLog.w("The session could not be cleared after deleting the account", it) }
                onSignedOut()
                AuthRepository.Deletion.DELETED
            },
            onFailure = { error ->
                MmLog.e("Deleting the account failed", error)
                AuthRepository.Deletion.FAILED
            },
        )
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
        ensureProfileRow(user)
        publish(user, offlineAccount = false)
    }

    /**
     * Makes sure the account has a row in `profiles` before anything is uploaded.
     *
     * Every table's `user_id` is a foreign key to that row, so without it the
     * first sync of every table fails on it. The schema creates the row from a
     * trigger on `auth.users`; this write is the second path, so a project whose
     * schema predates that trigger still works, and it is the only place the
     * display name the user typed can reach the server.
     *
     * A failure here is logged and swallowed on purpose: signing in must never
     * fail because a profile row could not be written. If it did fail, the sync
     * reports the foreign key error on the next run, which is where a user can
     * see it.
     */
    private suspend fun ensureProfileRow(user: User) {
        val client = supabaseProvider.get() ?: return
        runCatching {
            client.postgrest.from(PROFILE_TABLE).upsert(
                ProfileRecord(
                    id = user.id,
                    email = user.email,
                    displayName = user.displayName,
                ),
            )
        }.onFailure { error ->
            MmLog.w("Could not write the account's profile row", error)
        }
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

        /** Not a sync table; see [ProfileRecord] and the schema's trigger. */
        const val PROFILE_TABLE = "profiles"
    }
}
