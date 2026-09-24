package com.memorymap.data.sync

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One synchronisation run, against a table that can be told to fail.
 *
 * The cases that matter are the unhappy ones: what happens to the queue when a
 * request fails, and whether a record deleted offline can come back.
 */
class SyncEngineTest {

    private val engine = SyncEngine()
    private val table = FakeTable("memories")

    @Test
    fun `living rows are uploaded and then marked synced`() = runTest {
        table.queue += PendingRow("a", NOW, deleted = false)
        table.queue += PendingRow("b", NOW, deleted = false)

        val result = engine.sync(listOf(table), USER, since = null, now = NOW)

        assertEquals(listOf("a", "b"), table.sentUpserts)
        assertEquals(emptyList<String>(), table.sentDeletes)
        assertEquals(listOf("a", "b"), table.synced)
        assertEquals(NOW, table.syncedAt)
        assertEquals(2, result.report.uploaded)
        assertEquals(0, result.report.failed)
        assertTrue(result.report.succeeded)
    }

    @Test
    fun `a tombstone is sent as a delete rather than as an update`() = runTest {
        table.queue += PendingRow("gone", NOW, deleted = true)
        table.queue += PendingRow("here", NOW, deleted = false)

        val result = engine.sync(listOf(table), USER, since = null, now = NOW)

        // Sending the tombstone as a living row would put the record back.
        assertEquals(listOf("gone"), table.sentDeletes)
        assertEquals(listOf("here"), table.sentUpserts)
        assertEquals(1, result.report.deleted)
        assertEquals(1, result.report.uploaded)
    }

    @Test
    fun `a failed upload marks the rows as errored so the next run retries them`() = runTest {
        table.queue += PendingRow("a", NOW, deleted = false)
        table.failUploads = true

        val result = engine.sync(listOf(table), USER, since = null, now = NOW)

        assertEquals(listOf("a"), table.errored)
        assertEquals(emptyList<String>(), table.synced)
        assertEquals(1, result.report.failed)
        assertEquals(0, result.report.uploaded)
        assertTrue(result.report.errors.single().startsWith("memories:"))
    }

    @Test
    fun `a failed download leaves the local rows alone`() = runTest {
        table.server += RemoteRow("a", NEWER, deleted = false)
        table.failFetch = true

        val result = engine.sync(listOf(table), USER, since = null, now = NOW)

        assertEquals(emptyList<String>(), table.storedIds)
        assertEquals(1, result.report.failed)
        assertEquals(0, result.report.downloaded)
    }

    @Test
    fun `a row this device has never seen is stored`() = runTest {
        table.server += RemoteRow("new", NEWER, deleted = false)

        val result = engine.sync(listOf(table), USER, since = null, now = NOW)

        assertEquals(listOf("new"), table.storedIds)
        assertEquals(1, result.report.downloaded)
    }

    @Test
    fun `the newer side of a conflict is the one that is kept`() = runTest {
        // Local is newer: the incoming copy is older and must be dropped.
        table.local["a"] = LocalRow("a", NEWER, deleted = false)
        table.server += RemoteRow("a", OLDER, deleted = false)
        // Local is older: the incoming copy wins.
        table.local["b"] = LocalRow("b", OLDER, deleted = false)
        table.server += RemoteRow("b", NEWER, deleted = false)

        engine.sync(listOf(table), USER, since = null, now = NOW)

        assertEquals(listOf("b"), table.storedIds)
    }

    @Test
    fun `a local delete is never overwritten by a live server row`() = runTest {
        // The server has a newer, living copy; the user deleted it here while
        // offline. The delete must hold, or the record comes back.
        table.local["gone"] = LocalRow("gone", OLDER, deleted = true)
        table.server += RemoteRow("gone", NEWER, deleted = false)

        val result = engine.sync(listOf(table), USER, since = null, now = NOW)

        assertEquals(emptyList<String>(), table.storedIds)
        assertEquals(0, result.report.downloaded)
    }

    @Test
    fun `the watermark advances to the newest server timestamp`() = runTest {
        table.server += RemoteRow("a", OLDER, deleted = false)
        table.server += RemoteRow("b", NEWER, deleted = false)

        val result = engine.sync(listOf(table), USER, since = null, now = NOW)

        assertEquals(NEWER, result.watermark)
    }

    @Test
    fun `the watermark is kept when nothing came down`() = runTest {
        val result = engine.sync(listOf(table), USER, since = OLDER, now = NOW)

        assertEquals(OLDER, result.watermark)
    }

    @Test
    fun `the watermark is passed on so only changed rows are asked for`() = runTest {
        engine.sync(listOf(table), USER, since = OLDER, now = NOW)

        assertEquals(listOf(OLDER), table.seenSince)
    }

    @Test
    fun `an empty queue asks the server and changes nothing`() = runTest {
        val result = engine.sync(listOf(table), USER, since = null, now = NOW)

        assertTrue(result.report.isEmpty)
        assertEquals(emptyList<String>(), table.synced)
        assertEquals(emptyList<String>(), table.errored)
    }

    @Test
    fun `one table failing does not stop the other from syncing`() = runTest {
        val broken = FakeTable("daily_entries").apply {
            queue += PendingRow("x", NOW, deleted = false)
            failUploads = true
        }
        table.queue += PendingRow("a", NOW, deleted = false)

        val result = engine.sync(listOf(broken, table), USER, since = null, now = NOW)

        assertEquals(listOf("a"), table.synced)
        assertEquals(listOf("x"), broken.errored)
        assertEquals(1, result.report.uploaded)
        assertEquals(1, result.report.failed)
    }

    @Test
    fun `a watermark from an unstarted account is null`() = runTest {
        assertNull(engine.sync(listOf(table), USER, since = null, now = NOW).watermark)
    }

    /** A table the test drives by hand, and can make fail on demand. */
    private class FakeTable(override val name: String) : SyncTable<RemoteRow> {
        val queue = mutableListOf<PendingRow>()
        val local = mutableMapOf<String, LocalRow>()
        val server = mutableListOf<RemoteRow>()
        val sentUpserts = mutableListOf<String>()
        val sentDeletes = mutableListOf<String>()
        val synced = mutableListOf<String>()
        val errored = mutableListOf<String>()
        val storedIds = mutableListOf<String>()
        val seenSince = mutableListOf<String?>()
        var syncedAt: String? = null
        var failUploads = false
        var failDeletes = false
        var failFetch = false

        override suspend fun pending(userId: String): List<PendingRow> = queue.toList()

        override suspend fun pushUpserts(rows: List<PendingRow>) {
            if (failUploads) throw IOException("no network")
            sentUpserts += rows.map { it.id }
        }

        override suspend fun pushDeletes(rows: List<PendingRow>) {
            if (failDeletes) throw IOException("no network")
            sentDeletes += rows.map { it.id }
        }

        override suspend fun markSynced(ids: List<String>, at: String) {
            synced += ids
            syncedAt = at
        }

        override suspend fun markError(ids: List<String>) {
            errored += ids
        }

        override suspend fun fetchChanged(userId: String, since: String?): List<RemoteRow> {
            seenSince += since
            if (failFetch) throw IOException("no network")
            return server.toList()
        }

        override fun remoteInfo(row: RemoteRow): RemoteRow = row

        override suspend fun localSnapshot(ids: List<String>): Map<String, LocalRow> =
            ids.mapNotNull { local[it] }.associateBy { it.id }

        override suspend fun storeRemote(rows: List<RemoteRow>) {
            storedIds += rows.map { it.id }
        }
    }

    private companion object {
        const val USER = "user-1"
        const val OLDER = "2026-09-20T08:00:00Z"
        const val NEWER = "2026-09-24T08:00:00Z"
        const val NOW = "2026-09-24T12:00:00"
    }
}
