package com.memorymap.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.model.AuthState
import com.memorymap.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the sign-in form is currently doing. */
enum class AuthMode { SIGN_IN, SIGN_UP, RESET_PASSWORD }

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val mode: AuthMode = AuthMode.SIGN_IN,
    val isSubmitting: Boolean = false,
    /** A strings.xml key, or null when there is nothing to report. */
    val messageKey: String? = null,
)

/**
 * Drives the sign-in screen. Session restoration runs first so a returning user
 * is not asked to sign in again.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Unknown)

    val isCloudConfigured: Boolean = authRepository.isCloudConfigured

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { authRepository.restoreSession() }
    }

    fun onEmailChange(value: String) = _ui.update { it.copy(email = value, messageKey = null) }
    fun onPasswordChange(value: String) = _ui.update { it.copy(password = value, messageKey = null) }
    fun onDisplayNameChange(value: String) = _ui.update { it.copy(displayName = value, messageKey = null) }
    fun onModeChange(mode: AuthMode) = _ui.update { it.copy(mode = mode, messageKey = null) }

    fun submit() {
        val state = _ui.value
        if (state.isSubmitting) return

        val email = state.email.trim()
        if (email.isEmpty()) {
            _ui.update { it.copy(messageKey = "auth_error_email_required") }
            return
        }
        if (state.mode != AuthMode.RESET_PASSWORD && state.password.length < MIN_PASSWORD_LENGTH) {
            _ui.update { it.copy(messageKey = "auth_error_weak_password") }
            return
        }

        _ui.update { it.copy(isSubmitting = true, messageKey = null) }
        viewModelScope.launch {
            val result = when (state.mode) {
                AuthMode.SIGN_IN -> authRepository.signIn(email, state.password)
                AuthMode.SIGN_UP -> authRepository.signUp(email, state.password, state.displayName.trim())
                AuthMode.RESET_PASSWORD -> authRepository.resetPassword(email)
            }
            _ui.update { current ->
                current.copy(
                    isSubmitting = false,
                    messageKey = when (result) {
                        AuthRepository.Result.Success ->
                            if (state.mode == AuthMode.RESET_PASSWORD) "auth_reset_sent" else null
                        is AuthRepository.Result.Failure -> result.messageKey
                    },
                    // After a reset email is sent, go back to the sign-in form.
                    mode = if (state.mode == AuthMode.RESET_PASSWORD && result is AuthRepository.Result.Success) {
                        AuthMode.SIGN_IN
                    } else {
                        state.mode
                    },
                )
            }
        }
    }

    /** Continues without any account. Everything stays on the device. */
    fun continueOffline() {
        viewModelScope.launch { authRepository.continueOffline(_ui.value.displayName.trim()) }
    }

    private companion object {
        const val MIN_PASSWORD_LENGTH = 6
    }
}
