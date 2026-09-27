package com.memorymap.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The merge rule an import applies to one record.
 *
 * The case that matters most is the one that protects a delete: restoring an
 * older archive must not bring back something the user removed on purpose.
 */
class BackupMergeTest {

    private val older = "2024-03-01T10:00:00"
    private val newer = "2025-06-15T18:30:00"

    @Test
    fun `a record this device has never seen is inserted`() {
        assertEquals(
            BackupMerge.Decision.INSERT,
            BackupMerge.decide(existing = null, archivedUpdatedAt = older),
        )
    }

    @Test
    fun `an archived record newer than the local one replaces it`() {
        val decision = BackupMerge.decide(
            existing = BackupMerge.Existing(updatedAt = older, deletedAt = null),
            archivedUpdatedAt = newer,
        )
        assertEquals(BackupMerge.Decision.UPDATE, decision)
    }

    @Test
    fun `a newer local record is never overwritten by an older archive`() {
        val decision = BackupMerge.decide(
            existing = BackupMerge.Existing(updatedAt = newer, deletedAt = null),
            archivedUpdatedAt = older,
        )
        assertEquals(BackupMerge.Decision.SKIP, decision)
    }

    @Test
    fun `a tie keeps the local copy because that is the one on screen`() {
        val decision = BackupMerge.decide(
            existing = BackupMerge.Existing(updatedAt = newer, deletedAt = null),
            archivedUpdatedAt = newer,
        )
        assertEquals(BackupMerge.Decision.SKIP, decision)
    }

    @Test
    fun `a record deleted here stays deleted however recent the archive is`() {
        val decision = BackupMerge.decide(
            existing = BackupMerge.Existing(updatedAt = older, deletedAt = older),
            archivedUpdatedAt = newer,
        )
        assertEquals(BackupMerge.Decision.SKIP, decision)
    }

    @Test
    fun `an unreadable local timestamp keeps the local copy`() {
        val decision = BackupMerge.decide(
            existing = BackupMerge.Existing(updatedAt = "not a timestamp", deletedAt = null),
            archivedUpdatedAt = newer,
        )
        assertEquals(BackupMerge.Decision.SKIP, decision)
    }

    @Test
    fun `an unreadable archived timestamp keeps the local copy`() {
        val decision = BackupMerge.decide(
            existing = BackupMerge.Existing(updatedAt = older, deletedAt = null),
            archivedUpdatedAt = "",
        )
        assertEquals(BackupMerge.Decision.SKIP, decision)
    }

    @Test
    fun `an offset timestamp is compared as an instant not as text`() {
        // 09:00+02:00 is 07:00Z, earlier than 08:00Z, even though "09" sorts
        // after "08" as a string.
        val decision = BackupMerge.decide(
            existing = BackupMerge.Existing(updatedAt = "2024-03-01T09:00:00+02:00", deletedAt = null),
            archivedUpdatedAt = "2024-03-01T08:00:00Z",
        )
        assertEquals(BackupMerge.Decision.UPDATE, decision)
    }
}
