package com.memorymap.domain.model

/**
 * Authentication state of the app.
 *
 * [Unknown] exists so the UI can wait for session restoration instead of
 * flashing the sign-in screen at every cold start. [SignedIn] covers both a
 * Supabase account and the offline local account: after the first sign-in the
 * diary must keep working with no connection at all.
 */
sealed interface AuthState {

    /** Session restoration has not finished yet. */
    data object Unknown : AuthState

    /** No account is active. */
    data object SignedOut : AuthState

    /**
     * An account is active.
     *
     * @param offlineAccount true when the user is working against the local
     * account because no Supabase project is configured.
     */
    data class SignedIn(
        val user: User,
        val offlineAccount: Boolean,
    ) : AuthState
}
