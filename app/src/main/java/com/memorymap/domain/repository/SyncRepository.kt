package com.memorymap.domain.repository

import com.memorymap.domain.model.SyncState
import kotlinx.coroutines.flow.Flow

/**
 * The only door the UI has into synchronisation.
 *
 * It exposes a state to show and one action to trigger. Everything underneath —
 * the queue, the watermark, the conflict policy — stays in the data layer.
 */
interface SyncRepository {

    /** A live view of the queue and the last run, for the profile screen. */
    fun watchState(userId: String?): Flow<SyncState>

    /**
     * Runs one synchronisation now and returns the resulting state.
     *
     * Safe to call with no user or no cloud configuration: both are supported
     * states, and in either case nothing is attempted and nothing is lost.
     */
    suspend fun syncNow(userId: String?): SyncState
}
