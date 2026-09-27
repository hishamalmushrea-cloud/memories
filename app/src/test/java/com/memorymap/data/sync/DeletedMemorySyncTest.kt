package com.memorymap.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.remote.MemoryRecord
import com.memorymap.data.repository.MemoryRepositoryImpl
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.SyncStatus
import com.memorymap.domain.model.Visibility
import com.memorymap.testing.RecordingSyncApi
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Deleted memories, followed all the way through a sync run.
 *
 * The rule the spec names out loud is that a memory deleted offline must not come
 * back the next time the phone finds a network. [com.memorymap.domain.usecase.ConflictResolverTest]
 * proves the decision in isolation; this runs the same decision through the real
 * table, the real Room database and the real engine, because the decision is the
 * easy part - the failure would be in what the engine does with it: sending a
 * tombstone as if it were an edit, or letting the server's older copy win a pull
 * because the local row is no longer "newer".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeletedMemorySyncTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var api: RecordingSyncApi
    private lateinit var repository: MemoryRepositoryImpl
    private lateinit var table: MemorySyncTable
    private lateinit var engine: SyncEngine

    private val userId = "user-1"
    private val date = LocalDate.of(2026, 9, 23)
    private val now = "2026-09-25T10:00:00Z"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        api = RecordingSyncApi()
        repository = MemoryRepositoryImpl(db.memoryDao())
        table = MemorySyncTable(db.memoryDao(), api, clock = { now })
        engine = SyncEngine()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a memory deleted offline reaches the server as a deletion`() = runTest {
        val memory = memory("سأحذفها")
        repository.save(memory)

        repository.delete(memory.id)
        val result = engine.sync(listOf(table), userId, since = null, now = now)

        val sent = api.memoriesSent.single()
        assertEquals(memory.id, sent.id)
        // A delete travels as a tombstone. Sending it as a living row would put
        // the record back on the server instead of taking it away.
        assertNotNull("the row must arrive carrying its deletion", sent.deletedAt)
        assertEquals(1, result.report.deleted)
        assertEquals(0, result.report.uploaded)
    }

    @Test
    fun `the same run that sends the deletion cannot bring the memory back`() = runTest {
        val memory = memory("سأحذفها")
        repository.save(memory)
        repository.delete(memory.id)
        // The server still holds a live copy, newer than the tombstone: this is
        // the phone that deleted while offline meeting the copy it deleted.
        api.memoriesToReturn = listOf(record(memory.id, updatedAt = "2026-09-26T10:00:00Z"))

        engine.sync(listOf(table), userId, since = null, now = now)

        val stored = db.memoryDao().getById(memory.id)
        assertNotNull("the tombstone must survive the pull", stored!!.deletedAt)
        assertTrue(
            "the memory must not reappear in the list",
            repository.watchAll(userId).first().isEmpty(),
        )
    }

    @Test
    fun `a later run does not resurrect it either`() = runTest {
        val memory = memory("سأحذفها")
        repository.save(memory)
        repository.delete(memory.id)
        engine.sync(listOf(table), userId, since = null, now = now)
        assertEquals(SyncStatus.SYNCED.name, db.memoryDao().getById(memory.id)!!.syncStatus)

        // An hour later, a device that has not synced since it still had the
        // memory asks the server for everything, and gets a copy that is newer
        // than the deletion.
        api.memoriesToReturn = listOf(record(memory.id, updatedAt = "2026-09-26T10:00:00Z"))
        engine.sync(listOf(table), userId, since = null, now = "2026-09-26T12:00:00Z")

        assertNotNull(db.memoryDao().getById(memory.id)!!.deletedAt)
        assertTrue(repository.watchAll(userId).first().isEmpty())
    }

    @Test
    fun `a deletion made elsewhere follows the stamps and nothing else`() = runTest {
        // Two rows on this device, and a tombstone for each from the server: the
        // newer tombstone must be applied, the older one must not be allowed to
        // destroy an edit made here since. The stamps are written by hand rather
        // than taken from the clock, because the rule is a comparison and a test
        // that depended on the day it ran on would pass for the wrong reason.
        insert("id-older", updatedAt = "2026-09-20T10:00:00Z")
        insert("id-newer", updatedAt = "2026-09-26T10:00:00Z")
        api.memoriesToReturn = listOf(
            record("id-older", updatedAt = "2026-09-26T10:00:00Z", deletedAt = "2026-09-26T10:00:00Z"),
            record("id-newer", updatedAt = "2026-09-20T10:00:00Z", deletedAt = "2026-09-20T10:00:00Z"),
        )

        engine.sync(listOf(table), userId, since = null, now = now)

        val deleted = db.memoryDao().getById("id-older")!!
        assertNotNull("a tombstone newer than the local row must be stored", deleted.deletedAt)
        assertEquals(SyncStatus.SYNCED.name, deleted.syncStatus)

        val kept = db.memoryDao().getById("id-newer")!!
        assertEquals("a tombstone older than the local edit must be refused", null, kept.deletedAt)
        assertEquals(
            "only the surviving memory stays in the list",
            listOf("id-newer"),
            repository.watchAll(userId).first().map { it.id },
        )
        assertEquals(
            "a row deleted elsewhere makes nothing for the queue to send back",
            emptyList<String>(),
            db.memoryDao().pendingSync().map { it.id },
        )
    }

    /**
     * Writes a row straight into the table with a stamp this test chooses.
     *
     * It stands in for a row that has already synced once: no queue, and a moment
     * both sides agree about.
     */
    private suspend fun insert(id: String, updatedAt: String) {
        db.memoryDao().upsert(
            MemoryEntity(
                id = id,
                userId = userId,
                title = "موجودة",
                text = "",
                memoryDate = date.toString(),
                emotion = Emotion.NOSTALGIA.name,
                visibility = Visibility.PRIVATE.name,
                createdAt = "2026-09-10T10:00:00Z",
                updatedAt = updatedAt,
                syncStatus = SyncStatus.SYNCED.name,
            ),
        )
    }

    private fun memory(title: String) = Memory(
        userId = userId,
        title = title,
        memoryDate = date,
        emotion = Emotion.NOSTALGIA,
        visibility = Visibility.PRIVATE,
    )

    private fun record(
        id: String,
        updatedAt: String,
        deletedAt: String? = null,
    ) = MemoryRecord(
        id = id,
        userId = userId,
        title = "من الخادم",
        memoryDate = date.toString(),
        emotion = Emotion.NOSTALGIA.name,
        visibility = Visibility.PRIVATE.name,
        createdAt = "2026-09-20T10:00:00Z",
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )
}
