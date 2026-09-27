package com.memorymap.domain.usecase

import com.memorymap.domain.usecase.ConflictResolver.Winner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which copy wins.
 *
 * These are the rules that decide whether a user's writing survives, so each one
 * is pinned down — especially the rule that a local delete beats a live server
 * copy whatever the clocks say.
 */
class ConflictResolverTest {

    @Test
    fun `the newer edit wins`() {
        assertEquals(
            Winner.REMOTE,
            ConflictResolver.resolve(
                localUpdatedAt = OLDER,
                localDeleted = false,
                remoteUpdatedAt = NEWER,
                remoteDeleted = false,
            ),
        )
        assertEquals(
            Winner.LOCAL,
            ConflictResolver.resolve(
                localUpdatedAt = NEWER,
                localDeleted = false,
                remoteUpdatedAt = OLDER,
                remoteDeleted = false,
            ),
        )
    }

    @Test
    fun `a tie keeps the local copy because that is the one the user sees`() {
        assertEquals(
            Winner.LOCAL,
            ConflictResolver.resolve(SAME, false, SAME, false),
        )
    }

    @Test
    fun `a local delete wins over a live server copy, even a newer one`() {
        // This is the rule that stops a record deleted on a plane from coming
        // back the moment the phone finds a network again.
        assertEquals(
            Winner.LOCAL,
            ConflictResolver.resolve(
                localUpdatedAt = OLDER,
                localDeleted = true,
                remoteUpdatedAt = NEWER,
                remoteDeleted = false,
            ),
        )
    }

    @Test
    fun `a server delete wins only when this device has not edited since`() {
        assertEquals(
            Winner.REMOTE,
            ConflictResolver.resolve(
                localUpdatedAt = OLDER,
                localDeleted = false,
                remoteUpdatedAt = NEWER,
                remoteDeleted = true,
            ),
        )
        assertEquals(
            Winner.LOCAL,
            ConflictResolver.resolve(
                localUpdatedAt = NEWER,
                localDeleted = false,
                remoteUpdatedAt = OLDER,
                remoteDeleted = true,
            ),
        )
    }

    @Test
    fun `when both sides deleted, the newer tombstone wins`() {
        assertEquals(
            Winner.REMOTE,
            ConflictResolver.resolve(OLDER, true, NEWER, true),
        )
        assertEquals(
            Winner.LOCAL,
            ConflictResolver.resolve(NEWER, true, OLDER, true),
        )
    }

    @Test
    fun `an unreadable timestamp never overwrites anything the user can see`() {
        assertEquals(Winner.LOCAL, ConflictResolver.resolve("not a date", false, NEWER, false))
        assertEquals(Winner.LOCAL, ConflictResolver.resolve(NEWER, false, "not a date", false))
        assertEquals(Winner.LOCAL, ConflictResolver.resolve(null, false, NEWER, false))
        assertEquals(Winner.LOCAL, ConflictResolver.resolve(NEWER, false, null, false))
        assertEquals(Winner.LOCAL, ConflictResolver.resolve("", false, NEWER, false))
    }

    @Test
    fun `both server instants and naive local text are readable`() {
        assertNotNull(ConflictResolver.parse("2026-09-24T18:03:11.482Z"))
        assertNotNull(ConflictResolver.parse("2026-09-24T18:03:11.482+03:00"))
        assertNotNull(ConflictResolver.parse("2026-09-24T18:03:11.482"))
        assertNull(ConflictResolver.parse("yesterday"))
        assertNull(ConflictResolver.parse(null))
        assertNull(ConflictResolver.parse("  "))
    }

    @Test
    fun `the same instant written two ways compares equal`() {
        val asInstant = "2026-09-24T18:03:11Z"
        assertEquals(
            ConflictResolver.parse(asInstant),
            ConflictResolver.parse(asInstant),
        )
        // A one-second difference is not lost in the comparison.
        assertEquals(
            Winner.REMOTE,
            ConflictResolver.resolve(asInstant, false, "2026-09-24T18:03:12Z", false),
        )
    }

    private companion object {
        const val OLDER = "2026-09-20T08:00:00"
        const val NEWER = "2026-09-24T08:00:00"
        const val SAME = "2026-09-24T08:00:00"
    }
}
