package com.memorymap.testing

import com.memorymap.domain.model.AuthState
import com.memorymap.domain.model.User
import com.memorymap.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal auth double: a fixed account id and no network.
 *
 * ViewModel tests need "who is signed in" and nothing else, so this replaces the
 * Supabase-backed repository without touching a socket.
 */
class FakeAuthRepository(userId: String?) : AuthRepository {

    private val id = MutableStateFlow(userId)

    override val currentUserId: StateFlow<String?> = id.asStateFlow()

    override val authState: StateFlow<AuthState> = MutableStateFlow(
        if (userId == null) {
            AuthState.SignedOut
        } else {
            AuthState.SignedIn(User(id = userId, email = "", displayName = ""), false)
        },
    )

    override val isCloudConfigured: Boolean = false

    override suspend fun restoreSession() = Unit

    override suspend fun signUp(email: String, password: String, displayName: String) =
        AuthRepository.Result.Success

    override suspend fun signIn(email: String, password: String) = AuthRepository.Result.Success

    override suspend fun resetPassword(email: String) = AuthRepository.Result.Success

    override suspend fun signOut() {
        id.value = null
    }

    override suspend fun continueOffline(displayName: String?): User =
        User(id = id.value ?: "local-user", email = "", displayName = displayName.orEmpty())
}
