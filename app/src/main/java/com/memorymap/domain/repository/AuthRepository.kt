package com.memorymap.domain.repository

import com.memorymap.domain.model.AuthState
import com.memorymap.domain.model.User
import kotlinx.coroutines.flow.StateFlow

/**
 * The id used by the local, offline account. Records created before signing in
 * belong to it, which is what keeps the archive readable with no server.
 */
const val LOCAL_USER_ID = "local-user"

/**
 * Create, restore and end a session.
 *
 * Two implementations of "signed in" are supported on purpose:
 *  - a Supabase account, when a project is configured;
 *  - a local account, when it is not. The app is fully usable either way.
 */
interface AuthRepository {

    /** The live authentication state, [AuthState.Unknown] until restored. */
    val authState: StateFlow<AuthState>

    /** The owner id every query is scoped to; null while unknown. */
    val currentUserId: StateFlow<String?>

    /** True when a Supabase project is configured. */
    val isCloudConfigured: Boolean

    /** Restores a stored session. Called once when the app starts. */
    suspend fun restoreSession()

    /** Result wrapper so the UI never has to know about Supabase exceptions. */
    sealed interface Result {
        data object Success : Result
        data class Failure(val messageKey: String) : Result
    }

    suspend fun signUp(email: String, password: String, displayName: String): Result
    suspend fun signIn(email: String, password: String): Result
    suspend fun resetPassword(email: String): Result
    suspend fun signOut()

    /**
     * Continues with the local offline account. Nothing leaves the device and no
     * credential is stored: there is no password to check.
     */
    suspend fun continueOffline(displayName: String?): User
}
