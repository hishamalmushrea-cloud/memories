package com.memorymap.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.data.remote.EntryRecord
import com.memorymap.data.remote.MemoryRecord
import com.memorymap.data.remote.PersonRecord
import com.memorymap.data.remote.PlaceRecord
import com.memorymap.data.remote.SyncApi
import com.memorymap.domain.model.SyncStatus
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
 * People and places as synchronisable tables.
 *
 * These two were local-only until version 3, which meant reinstalling the app
 * lost every name the user had built up. What has to hold now is the same
 * contract the other tables have: a new row goes up marked pending, a deletion
 * travels as a tombstone rather than vanishing, and a failed send leaves the
 * row queued for the next run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReferenceSyncTableTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var api: RecordingSyncApi

    private val userId = "user-1"
    private val now = "2026-09-25T10:00:00"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        api = RecordingSyncApi()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a new person is queued, sent, and marked synced`() = runTest {
        db.personDao().upsert(person("p1", "أحمد"))
        val table = PersonSyncTable(db.personDao(), api, clock = { now })

        val pending = table.pending(userId)
        assertEquals(listOf("p1"), pending.map { it.id })
        assertEquals(false, pending.single().deleted)

        table.pushUpserts(pending)
        table.markSynced(pending.map { it.id }, now)

        assertEquals("أحمد", api.peopleSent.single().name)
        assertEquals(SyncStatus.SYNCED.name, db.personDao().getById("p1")!!.syncStatus)
        // Nothing is left in the queue once the server has the row.
        assertTrue(table.pending(userId).isEmpty())
    }

    @Test
    fun `a new place is queued, sent, and marked synced`() = runTest {
        db.placeDao().upsert(place("pl1", "إب"))
        val table = PlaceSyncTable(db.placeDao(), api, clock = { now })

        val pending = table.pending(userId)
        assertEquals(listOf("pl1"), pending.map { it.id })

        table.pushUpserts(pending)
        table.markSynced(pending.map { it.id }, now)

        val sent = api.placesSent.single()
        assertEquals("إب", sent.name)
        assertEquals(13.97, sent.latitude, 0.0001)
        assertEquals(SyncStatus.SYNCED.name, db.placeDao().getById("pl1")!!.syncStatus)
    }

    @Test
    fun `a deletion travels as a tombstone, not as a vanished row`() = runTest {
        db.personDao().upsert(person("p1", "أحمد"))
        val table = PersonSyncTable(db.personDao(), api, clock = { now })
        db.personDao().softDelete("p1", now)

        val pending = table.pending(userId)
        // The engine splits on this flag; getting it wrong would resurrect the
        // person on the server instead of removing them.
        assertTrue(pending.single().deleted)

        table.pushDeletes(pending)

        assertEquals(now, api.peopleSent.single().deletedAt)
    }

    @Test
    fun `a failed send leaves the row queued for the next run`() = runTest {
        db.personDao().upsert(person("p1", "أحمد"))
        val table = PersonSyncTable(db.personDao(), api, clock = { now })

        table.markError(listOf("p1"))

        assertEquals(SyncStatus.SYNC_ERROR.name, db.personDao().getById("p1")!!.syncStatus)
        assertEquals(listOf("p1"), table.pending(userId).map { it.id })
    }

    @Test
    fun `a downloaded person is stored and considered in agreement`() = runTest {
        val table = PersonSyncTable(db.personDao(), api, clock = { now })

        table.storeRemote(
            listOf(
                PersonRecord(
                    id = "p9",
                    userId = userId,
                    name = "سعاد",
                    createdAt = "2024-01-01T00:00:00Z",
                    updatedAt = "2024-01-02T00:00:00Z",
                ),
            ),
        )

        val stored = db.personDao().getById("p9")!!
        assertEquals("سعاد", stored.name)
        // A row that came from the server is by definition not pending, or every
        // download would be uploaded straight back.
        assertEquals(SyncStatus.SYNCED.name, stored.syncStatus)
        assertTrue(table.pending(userId).isEmpty())
    }

    @Test
    fun `remote rows are compared on local text, not on the raw instant`() {
        val table = PersonSyncTable(db.personDao(), api, clock = { now })

        val info = table.remoteInfo(
            PersonRecord(
                id = "p9",
                userId = userId,
                name = "سعاد",
                createdAt = "2024-01-01T00:00:00Z",
                updatedAt = "2024-01-02T03:04:05Z",
            ),
        )

        // Room stores naive local text; comparing an instant against it directly
        // would let a timezone decide who wins a conflict.
        assertEquals("2024-01-02T03:04:05", info.updatedAt)
        assertEquals(false, info.deleted)
    }

    private fun person(id: String, name: String) = PersonEntity(
        id = id,
        userId = userId,
        name = name,
        createdAt = "2024-01-01T00:00:00",
        updatedAt = "2024-01-01T00:00:00",
        syncStatus = SyncStatus.PENDING_CREATE.name,
    )

    private fun place(id: String, name: String) = PlaceEntity(
        id = id,
        userId = userId,
        name = name,
        latitude = 13.97,
        longitude = 44.17,
        createdAt = "2024-01-01T00:00:00",
        updatedAt = "2024-01-01T00:00:00",
        syncStatus = SyncStatus.PENDING_CREATE.name,
    )

    /** Records what was sent; the memory and entry halves are unused here. */
    private class RecordingSyncApi : SyncApi {
        val peopleSent = mutableListOf<PersonRecord>()
        val placesSent = mutableListOf<PlaceRecord>()

        override suspend fun upsertMemories(rows: List<MemoryRecord>) = Unit
        override suspend fun upsertEntries(rows: List<EntryRecord>) = Unit
        override suspend fun fetchMemories(userId: String, since: String?) = emptyList<MemoryRecord>()
        override suspend fun fetchEntries(userId: String, since: String?) = emptyList<EntryRecord>()

        override suspend fun upsertPeople(rows: List<PersonRecord>) {
            peopleSent += rows
        }

        override suspend fun upsertPlaces(rows: List<PlaceRecord>) {
            placesSent += rows
        }

        override suspend fun fetchPeople(userId: String, since: String?) = emptyList<PersonRecord>()
        override suspend fun fetchPlaces(userId: String, since: String?) = emptyList<PlaceRecord>()
    }
}
