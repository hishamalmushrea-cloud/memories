package com.memorymap.ui.share

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * Lets the app shell notice that a share is waiting.
 *
 * Thin on purpose: [PendingShare] holds the snippet and the editor consumes it,
 * so this only surfaces the "something is pending" signal the shell navigates on.
 * The shell cannot reach the singleton directly - a composable gets its state
 * through a view model - and it must react even when the share arrived while a
 * gate was up, which is why the signal is a stream and not a one-shot read.
 */
@HiltViewModel
class ShareViewModel @Inject constructor(
    pendingShare: PendingShare,
) : ViewModel() {

    /** The snippet waiting to be saved, or null when nothing is pending. */
    val pendingText: StateFlow<String?> = pendingShare.text
}
