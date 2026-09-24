package com.memorymap.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.data.remote.EntryPersonLink
import com.memorymap.data.remote.EntryPlaceLink
import com.memorymap.data.remote.EntryRecord
import com.memorymap.data.remote.MemoryPersonLink
import com.memorymap.data.remote.MemoryPlaceLink
import com.memorymap.data.remote.MemoryRecord
import com.memorymap.domain.model.SyncStatus
import com.memorymap.testing.RecordingSyncApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The links between records and the people and places they mention.
 *
 * The point of these is that a link is not a row with a history of its own: it
 * has no timestamp and nothing to resolve a conflict with. So it travels with
 * the record that owns it and is replaced wholesale in both directions. What
 * that has to buy is the one thing merging could never express - an unlink.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LinkSyncTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var api: RecordingSyncApi

    private val userId = "user-1"
    private val now = "2026-09-25T10:00:00"
    private val stamp = "2024-01-01T00:00:00"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        api = RecordingSyncApi()
        // The server's link tables carry a foreign key to people and places, so
        // a link can only be stored once the name it points at exists.
        db.personDao().upsert(PersonEntity(id = "p1", userId = userId, name = "أحمد", createdAt = stamp))
        db.personDao().upsert(PersonEntity(id = "p2", userId = userId, name = "سعاد", createdAt = stamp))
        db.placeDao().upsert(
            PlaceEntity(id = "pl1", userId = userId, name = "إب", latitude = 13.97, longitude = 44.17, createdAt = stamp),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `pushing a memory sends the people and places it mentions`() = runTest {
        db.memoryDao().upsert(memory("m1"))
        db.memoryDao().replacePeople("m1", listOf("p1", "p2"))
        db.memoryDao().replacePlaces("m1", listOf("pl1"))
        val table = MemorySyncTable(db.memoryDao(), api, clock = { now })

        table.pushUpserts(table.pending(userId))

        // The record goes first, because the server cannot accept a link to a
        // memory it does not hold yet.
        assertEquals(listOf("m1"), api.memoriesSent.map { it.id })
        val (ids, links) = api.memoryPeopleReplacements.single()
        assertEquals(listOf("m1"), ids)
        assertEquals(setOf("p1", "p2"), links.map { it.personId }.toSet())
        assertEquals(
            setOf("pl1"),
            api.memoryPlaceReplacements.single().second.map { it.placeId }.toSet(),
        )
    }

    @Test
    fun `pushing a memory whose links were all removed says so explicitly`() = runTest {
        db.memoryDao().upsert(memory("m1"))
        db.memoryDao().replacePeople("m1", listOf("p1"))
        val table = MemorySyncTable(db.memoryDao(), api, clock = { now })
        db.memoryDao().replacePeople("m1", emptyList())

        table.pushUpserts(table.pending(userId))

        // The id must still be in the call. A replace that is skipped because
        // there is nothing to send would leave the old link on the server
        // forever, which is exactly the failure an unlink has to avoid.
        val (ids, links) = api.memoryPeopleReplacements.single()
        assertEquals(listOf("m1"), ids)
        assertTrue(links.isEmpty())
    }

    @Test
    fun `pushing an event sends its links the same way`() = runTest {
        db.dailyEntryDao().upsert(entry("e1"))
        db.dailyEntryDao().replacePeople("e1", listOf("p2"))
        db.dailyEntryDao().replacePlaces("e1", listOf("pl1"))
        val table = EntrySyncTable(db.dailyEntryDao(), api, clock = { now })

        table.pushUpserts(table.pending(userId))

        assertEquals(listOf("e1"), api.entriesSent.map { it.id })
        assertEquals(
            setOf("p2"),
            api.entryPeopleReplacements.single().second.map { it.personId }.toSet(),
        )
        assertEquals(
            setOf("pl1"),
            api.entryPlaceReplacements.single().second.map { it.placeId }.toSet(),
        )
    }

    @Test
    fun `a downloaded memory brings its links with it`() = runTest {
        val table = MemorySyncTable(db.memoryDao(), api, clock = { now })
        api.memoriesToReturn = listOf(memoryRecord("m9"))
        api.memoryPeopleToReturn = listOf(MemoryPersonLink("m9", "p1"), MemoryPersonLink("m9", "p2"))
        api.memoryPlacesToReturn = listOf(MemoryPlaceLink("m9", "pl1"))

        val incoming = table.fetchChanged(userId, since = null)
        table.storeRemote(incoming)

        assertEquals(setOf("p1", "p2"), db.memoryDao().peopleOf("m9").toSet())
        assertEquals(setOf("pl1"), db.memoryDao().placesOf("m9").toSet())
    }

    @Test
    fun `a download replaces local links rather than merging into them`() = runTest {
        db.memoryDao().upsert(memory("m9"))
        db.memoryDao().replacePeople("m9", listOf("p1", "p2"))
        val table = MemorySyncTable(db.memoryDao(), api, clock = { now })
        api.memoriesToReturn = listOf(memoryRecord("m9"))
        // The other device unlinked سعاد; only أحمد remains.
        api.memoryPeopleToReturn = listOf(MemoryPersonLink("m9", "p1"))

        table.storeRemote(table.fetchChanged(userId, since = null))

        assertEquals(setOf("p1"), db.memoryDao().peopleOf("m9").toSet())
    }

    @Test
    fun `a downloaded event brings its links with it`() = runTest {
        val table = EntrySyncTable(db.dailyEntryDao(), api, clock = { now })
        api.entriesToReturn = listOf(entryRecord("e9"))
        api.entryPeopleToReturn = listOf(EntryPersonLink("e9", "p2"))
        api.entryPlacesToReturn = listOf(EntryPlaceLink("e9", "pl1"))

        table.storeRemote(table.fetchChanged(userId, since = null))

        assertEquals(setOf("p2"), db.dailyEntryDao().peopleOf("e9").toSet())
        assertEquals(setOf("pl1"), db.dailyEntryDao().placesOf("e9").toSet())
    }

    @Test
    fun `a memory with no links still resolves to an empty set, not a failed read`() = runTest {
        val table = MemorySyncTable(db.memoryDao(), api, clock = { now })
        api.memoriesToReturn = listOf(memoryRecord("m9"))

        val incoming = table.fetchChanged(userId, since = null)

        // A record nobody is mentioned in is the common case; it must not be
        // confused with a record whose links failed to load.
        assertTrue(incoming.single().personIds.isEmpty())
        assertTrue(incoming.single().placeIds.isEmpty())
    }

    @Test
    fun `nothing is fetched when the server has no changes`() = runTest {
        val table = MemorySyncTable(db.memoryDao(), api, clock = { now })
        api.memoriesToReturn = emptyList()

        assertTrue(table.fetchChanged(userId, since = null).isEmpty())
        // Asking for the links of no records would be a wasted round trip.
        assertTrue(api.memoryPeopleToReturn.isEmpty())
    }

    private fun memory(id: String) = MemoryEntity(
        id = id,
        userId = userId,
        title = "رحلة إلى إب",
        text = "",
        memoryDate = "2026-09-25",
        emotion = "NOSTALGIA",
        visibility = "PRIVATE",
        createdAt = stamp,
        updatedAt = stamp,
        syncStatus = SyncStatus.PENDING_CREATE.name,
    )

    private fun entry(id: String) = DailyEntryEntity(
        id = id,
        userId = userId,
        date = "2026-09-25",
        time = stamp,
        title = "زيارة أحمد",
        text = "",
        createdAt = stamp,
        updatedAt = stamp,
        syncStatus = SyncStatus.PENDING_CREATE.name,
    )

    private fun memoryRecord(id: String) = MemoryRecord(
        id = id,
        userId = userId,
        title = "رحلة إلى إب",
        memoryDate = "2026-09-25",
        emotion = "NOSTALGIA",
        visibility = "PRIVATE",
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2026-09-25T10:00:00Z",
    )

    private fun entryRecord(id: String) = EntryRecord(
        id = id,
        userId = userId,
        entryDate = "2026-09-25",
        entryTime = "2026-09-25T10:00:00Z",
        title = "زيارة أحمد",
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2026-09-25T10:00:00Z",
    )
}
