package com.memorymap.ui.share

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * One shared snippet, held until the editor can take it.
 *
 * A share can arrive at any moment - including while the app is still showing the
 * lock screen or the sign-in form, both of which sit in front of the editor. The
 * text therefore cannot live in navigation state, which does not exist yet at that
 * point; it is held here, above the gates, and consumed once the user reaches a
 * new record. A singleton for the same reason: it has to outlive every screen
 * between the intent and the editor.
 *
 * It holds at most one snippet by design. A second share replaces the first rather
 * than queueing, because the user's latest intent is the one they mean.
 */
@Singleton
class PendingShare @Inject constructor() {

    private val _text = MutableStateFlow<String?>(null)

    /** The snippet waiting to be saved, or null when there is nothing pending. */
    val text: StateFlow<String?> = _text.asStateFlow()

    /** Stores a shared snippet, replacing any that has not been consumed yet. */
    fun set(value: String) {
        _text.value = value
    }

    /** Returns the pending snippet and clears it, so it is applied exactly once. */
    fun consume(): String? = _text.getAndUpdate { null }
}
