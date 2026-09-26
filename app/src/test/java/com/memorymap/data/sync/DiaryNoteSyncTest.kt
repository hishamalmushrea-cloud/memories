package com.memorymap.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.entities.DiaryNoteEntity
import com.memorymap.data.remote.DiaryNoteRecord
import com.memorymap.domain.model.SyncStatus
import com.memorymap.testing.RecordingSyncApi
import com.memorymap.util.SyncTime
import java.time.LocalDateTime
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
 * The day's note as a synchronisable table.
 *
 * It was not one until now: the note was written into Room, the schema had a
 * table waiting for it, and nothing carried it to the server, so signing in on
 * another device showed the events of a day without the note about them. What has
 * to hold now is what holds for every other table - a new note is queued, a
 * successful send marks it synced, and a note edited elsewhere arrives when it is
 * newer than the local one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiaryNoteSyncTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var api: RecordingSyncApi

    private val userId = "user-1"
    private val day = "2026-09-20"
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
    fun `a new note is queued, sent, and marked synced`() = runTest {
        db.diaryNoteDao().upsert(note("كتبت عن يوم طويل"))
        val table = DiaryNoteSyncTable(db.diaryNoteDao(), api, clock = { now })

        val pending = table.pending(userId)
        assertEquals(1, pending.size)
        assertEquals(false, pending.single().deleted)

        table.pushUpserts(pending)
        table.markSynced(pending.map { it.id }, now)

        val sent = api.diaryNotesSent.single()
        assertEquals(userId, sent.userId)
        assertEquals(day, sent.noteDate)
        assertEquals("كتبت عن يوم طويل", sent.body)
        assertEquals(SyncStatus.SYNCED.name, db.diaryNoteDao().get(userId, day)!!.syncStatus)
        assertEquals(now, db.diaryNoteDao().get(userId, day)!!.lastSyncedAt)
        assertTrue("nothing should be left in the queue", table.pending(userId).isEmpty())
    }

    @Test
    fun `the queue excludes a note that already agrees with the server`() = runTest {
        db.diaryNoteDao().upsert(note("قديمة", status = SyncStatus.SYNCED.name))

        assertTrue(DiaryNoteSyncTable(db.diaryNoteDao(), api, clock = { now }).pending(userId).isEmpty())
    }

    @Test
    fun `a cleared note travels as an empty body, not as a deletion`() = runTest {
        // The schema has no `deleted_at` for a note, and the row is what records
        // that the user wrote that day at all, so clearing the text is an update.
        db.diaryNoteDao().upsert(note(""))
        val table = DiaryNoteSyncTable(db.diaryNoteDao(), api, clock = { now })

        val pending = table.pending(userId)
        table.pushUpserts(pending)

        assertEquals("", api.diaryNotesSent.single().body)
        assertEquals(emptyList<String>(), pending.filter { it.deleted }.map { it.id })
    }

    @Test
    fun `a failed send leaves the note queued and marked as an error`() = runTest {
        db.diaryNoteDao().upsert(note("نص"))
        val table = DiaryNoteSyncTable(db.diaryNoteDao(), api, clock = { now })

        val pending = table.pending(userId)
        table.markError(pending.map { it.id })

        assertEquals(SyncStatus.SYNC_ERROR.name, db.diaryNoteDao().get(userId, day)!!.syncStatus)
        assertEquals("the next run still sees it", 1, table.pending(userId).size)
        assertTrue("nothing was sent", api.diaryNotesSent.isEmpty())
    }

    @Test
    fun `a note from the server replaces the local text and lands as synced`() = runTest {
        db.diaryNoteDao().upsert(note("ما كتبته هنا"))
        val table = DiaryNoteSyncTable(db.diaryNoteDao(), api, clock = { now })

        table.storeRemote(listOf(record(body = "ما كُتب هناك", updatedAt = "2026-09-24T09:00:00Z")))

        assertEquals("ما كُتب هناك", db.diaryNoteDao().get(userId, day)!!.text)
        assertEquals(SyncStatus.SYNCED.name, db.diaryNoteDao().get(userId, day)!!.syncStatus)
        assertTrue("a stored remote row is not queued back up", table.pending(userId).isEmpty())
    }

    @Test
    fun `a remote stamp is compared in the same space as a local one`() = runTest {
        // The engine decides whether an incoming row wins by comparing its stamp
        // with the local one, and one of those is an instant while the other is
        // naive local text. Without this conversion the comparison would be
        // decided by the device's offset.
        db.diaryNoteDao().upsert(note("نص"))
        val table = DiaryNoteSyncTable(db.diaryNoteDao(), api, clock = { now })
        val instant = "2026-09-24T09:00:00Z"

        val remote = table.remoteInfo(record(updatedAt = instant))

        assertEquals(SyncTime.toLocalText(instant), remote.updatedAt)
        assertTrue(
            "the remote stamp still reads as the later of the two",
            LocalDateTime.parse(remote.updatedAt).isAfter(LocalDateTime.parse(note("x").updatedAt)),
        )
        assertEquals(1, table.localSnapshot(listOf(remote.id)).size)
    }

    @Test
    fun `the handle the engine uses is the one the database composes`() = runTest {
        // The engine carries one id per row, and this table's primary key is a
        // pair, so the handle is `user_id || '|' || date` in SQL and
        // `DiaryNoteSyncTable.noteHandle` in Kotlin. If the separator or the
        // order ever drifts, a note would be queued under an id nothing can
        // resolve and would be re-sent forever.
        db.diaryNoteDao().upsert(note("نص"))
        val table = DiaryNoteSyncTable(db.diaryNoteDao(), api, clock = { now })

        val handle = DiaryNoteSyncTable.noteHandle(userId, day)
        assertEquals(listOf(handle), table.pending(userId).map { it.id })
        assertEquals(handle, table.remoteInfo(record()).id)

        // The handle resolves back to the row, which is what push relies on.
        assertEquals(listOf(handle), db.diaryNoteDao().syncSnapshot(listOf(handle)).map { it.id })
        assertEquals(1, db.diaryNoteDao().byHandles(listOf(handle)).size)
        assertTrue(
            "an unknown handle must resolve to nothing rather than to another day",
            db.diaryNoteDao().byHandles(listOf("$userId|2026-01-01")).isEmpty(),
        )
    }

    private fun note(text: String, status: String = SyncStatus.PENDING_UPDATE.name) = DiaryNoteEntity(
        userId = userId,
        date = day,
        text = text,
        updatedAt = "2026-09-23T08:00:00",
        syncStatus = status,
    )

    private fun record(
        body: String = "نص",
        updatedAt: String = "2026-09-23T08:00:00Z",
    ) = DiaryNoteRecord(
        userId = userId,
        noteDate = day,
        body = body,
        updatedAt = updatedAt,
        syncStatus = SyncStatus.SYNCED.name,
        lastSyncedAt = LocalDateTime.parse(now).toString(),
    )
}
