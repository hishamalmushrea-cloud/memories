package com.memorymap.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MediaFileStore
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.entities.MediaEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.remote.MediaObjectKey
import com.memorymap.data.remote.MediaRecord
import com.memorymap.domain.model.SyncStatus
import com.memorymap.testing.RecordingImageOptimizer
import com.memorymap.testing.RecordingMediaStorage
import com.memorymap.testing.RecordingSyncApi
import com.memorymap.testing.TemporaryMediaFileStore
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The optional cloud copy of an attachment.
 *
 * Three things have to hold and none of them can be checked by reading the code:
 * an attachment nobody asked about never leaves the device, an attachment that
 * was asked about reaches the bucket under a key the storage policy will accept,
 * and one whose bytes cannot be moved is reported rather than quietly dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSyncTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: MemoryMapDatabase
    private lateinit var api: RecordingSyncApi
    private lateinit var storage: RecordingMediaStorage
    private lateinit var files: MediaFileStore
    private lateinit var images: RecordingImageOptimizer
    private lateinit var table: MediaSyncTable

    private val userId = "user-1"
    private val memoryId = "memory-1"
    private val stamp = "2024-01-01T00:00:00"

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        api = RecordingSyncApi()
        storage = RecordingMediaStorage()
        files = TemporaryMediaFileStore(temporaryFolder.root)
        images = RecordingImageOptimizer()
        table = MediaSyncTable(
            dao = db.mediaDao(),
            api = api,
            storage = storage,
            files = files,
            images = images,
            clock = { "2024-06-01T10:00:00" },
        )
        db.memoryDao().upsert(
            MemoryEntity(
                id = memoryId,
                userId = userId,
                title = "ذكرى",
                text = "",
                memoryDate = "2024-01-01",
                emotion = "HAPPY",
                visibility = "PRIVATE",
                createdAt = stamp,
                updatedAt = stamp,
                syncStatus = SyncStatus.SYNCED.name,
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `an attachment nobody asked for is never queued`() = runTest {
        attach(optIn = false)

        assertEquals(emptyList<PendingRow>(), table.pending(userId))
    }

    @Test
    fun `an opt-in attachment is queued once`() = runTest {
        attach(optIn = true)

        assertEquals(listOf("media-1"), table.pending(userId).map { it.id })
    }

    @Test
    fun `uploading puts the bytes in the bucket and then sends the row`() = runTest {
        attach(optIn = true)

        table.pushUpserts(table.pending(userId))

        // The first folder is the user id, which is what the storage policy
        // checks before it lets anything be written.
        val key = "$userId/media-1.jpg"
        assertTrue("objects were ${storage.objects.keys}", storage.objects.containsKey(key))
        assertEquals(5, storage.objects.getValue(key).size)

        val sent = api.mediaSent.single()
        assertEquals(key, sent.storagePath)
        assertEquals(memoryId, sent.ownerId)
        assertEquals("MEMORY", sent.ownerType)
        // The row names its account, because the server column is a foreign key
        // to the profile and an empty value would be refused.
        assertEquals(userId, sent.userId)
    }

    @Test
    fun `the uploaded row records where its bytes went`() = runTest {
        attach(optIn = true)

        table.pushUpserts(table.pending(userId))

        val row = db.mediaDao().getById("media-1")
        assertEquals("$userId/media-1.jpg", row!!.storagePath)
        // Recorded rather than assumed: the key has to outlive the request.
        assertNotNull(row.updatedAt)
    }

    @Test
    fun `what reaches the bucket is the prepared copy, not the file`() = runTest {
        attach(optIn = true)
        val prepared = byteArrayOf(9, 9, 9)
        images.replacement = prepared
        images.extension = "jpg"

        table.pushUpserts(table.pending(userId))

        assertEquals(listOf(prepared.toList()), storage.objects.map { it.value.toList() })
        assertEquals("$userId/media-1.jpg", api.mediaSent.single().storagePath)
        // The file on this device is read, never rewritten: the original is what
        // the user keeps, and only the copy that leaves the device is changed.
        assertEquals(
            byteArrayOf(1, 2, 3, 4, 5).toList(),
            File(db.mediaDao().getById("media-1")!!.uri).readBytes().toList(),
        )
    }

    @Test
    fun `the key follows the extension the preparation reported`() = runTest {
        // The extension is not cosmetic: it is what the next device names its own
        // file after. A preparation that reports one has to be believed, or a
        // photo would arrive wearing the wrong format.
        attach(optIn = true)
        images.replacement = byteArrayOf(7)
        images.extension = "webp"

        table.pushUpserts(table.pending(userId))

        assertEquals("$userId/media-1.webp", db.mediaDao().getById("media-1")!!.storagePath)
        assertEquals(setOf("$userId/media-1.webp"), storage.objects.keys)
    }

    @Test
    fun `an attachment the optimizer passes through keeps its own bytes and name`() = runTest {
        attach(optIn = true)

        table.pushUpserts(table.pending(userId))

        // Nothing is configured, so the preparation hands back what it was given:
        // this is the pass-through the other upload tests are built on, and it is
        // also what an image the optimizer cannot improve looks like.
        assertEquals(listOf(db.mediaDao().getById("media-1")!!.uri), images.asked)
        assertEquals(5, storage.objects.getValue("$userId/media-1.jpg").size)
    }

    @Test
    fun `an attachment whose file is gone fails instead of sending a row`() = runTest {
        attach(optIn = true)
        File(db.mediaDao().getById("media-1")!!.uri).delete()

        val failure = runCatching { table.pushUpserts(table.pending(userId)) }.exceptionOrNull()

        assertNotNull("a missing file has to be reported", failure)
        // Nothing was uploaded and nothing was sent, so the server never learns
        // about an attachment whose bytes are not there.
        assertEquals(emptyMap<String, ByteArray>(), storage.objects)
        assertEquals(emptyList<MediaRecord>(), api.mediaSent)
    }

    @Test
    fun `an unreachable bucket leaves the attachment queued`() = runTest {
        attach(optIn = true)
        storage.failing = true

        val failure = runCatching { table.pushUpserts(table.pending(userId)) }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(emptyList<MediaRecord>(), api.mediaSent)
        // Still waiting, so the next run tries again.
        assertEquals(listOf("media-1"), table.pending(userId).map { it.id })
    }

    @Test
    fun `an attachment already in the bucket is not uploaded twice`() = runTest {
        attach(optIn = true, storagePath = "$userId/media-1.jpg")

        table.pushUpserts(table.pending(userId))

        // The bytes were never read, because the row already says where they are.
        assertEquals(emptyMap<String, ByteArray>(), storage.objects)
        assertEquals("$userId/media-1.jpg", api.mediaSent.single().storagePath)
    }

    @Test
    fun `a downloaded attachment brings its bytes`() = runTest {
        val key = "$userId/media-9.jpg"
        storage.objects[key] = byteArrayOf(9, 8, 7)
        api.mediaToReturn = listOf(record(id = "media-9", storagePath = key))

        val incoming = table.fetchChanged(userId, null)
        table.storeRemote(incoming)

        val row = db.mediaDao().getById("media-9")
        assertNotNull(row)
        assertEquals(key, row!!.storagePath)
        assertTrue("the file should be on this device", File(row.uri).exists())
        assertEquals(3, File(row.uri).readBytes().size)
    }

    @Test
    fun `a row that arrives deleted is stored as a tombstone and fetches nothing`() = runTest {
        val key = "$userId/media-9.jpg"
        storage.objects[key] = byteArrayOf(1)
        api.mediaToReturn = listOf(
            record(id = "media-9", storagePath = key, deletedAt = "2024-05-01T00:00:00"),
        )

        table.storeRemote(table.fetchChanged(userId, null))

        val row = db.mediaDao().getById("media-9")
        assertNotNull(row!!.deletedAt)
        // Nothing to fetch for something that was deleted.
        assertEquals(1, storage.objects.size)
    }

    @Test
    fun `a downloaded attachment that cannot be fetched is reported`() = runTest {
        storage.failing = true
        api.mediaToReturn = listOf(record(id = "media-9", storagePath = "$userId/media-9.jpg"))

        val failure = runCatching {
            table.storeRemote(table.fetchChanged(userId, null))
        }.exceptionOrNull()

        assertNotNull("a download that failed has to be reported", failure)
        // The row is still stored, so the attachment is visible and its account
        // knows about it; only the bytes are missing.
        assertNotNull(db.mediaDao().getById("media-9"))
    }

    @Test
    fun `removing an uploaded attachment takes the object with it`() = runTest {
        attach(optIn = true)
        table.pushUpserts(table.pending(userId))
        val key = "$userId/media-1.jpg"
        assertTrue(storage.objects.containsKey(key))

        db.mediaDao().softDelete("media-1", "2024-07-01T00:00:00")
        table.pushDeletes(table.pending(userId))

        assertEquals(listOf(key), storage.removed)
        assertFalse(storage.objects.containsKey(key))
        // The row carried the tombstone first, so the other device learns of the
        // deletion even though the object is gone.
        assertNotNull(api.mediaSent.last().deletedAt)
    }

    @Test
    fun `an attachment deleted here but never uploaded leaves nothing behind`() = runTest {
        attach(optIn = false)
        db.mediaDao().softDelete("media-1", "2024-07-01T00:00:00")

        assertEquals(emptyList<PendingRow>(), table.pending(userId))
    }

    // --- the key, which the storage policy will police ---

    @Test
    fun `the object key starts with the account and keeps the extension`() {
        assertEquals("user-1/abc.jpg", MediaObjectKey.of("user-1", "abc", "jpg"))
        assertEquals("user-1/abc.bin", MediaObjectKey.of("user-1", "abc", ""))
        assertEquals("user-1/abc.jpg", MediaObjectKey.of("user-1", "abc", ".JPG"))
    }

    @Test
    fun `a bare extension and a path are told apart`() {
        // The two entry points take different things, and passing one where the
        // other is expected is how an object ends up named `...bin`.
        assertEquals("jpg", MediaObjectKey.extensionOf("/storage/pic.jpg"))
        assertEquals("jpg", MediaObjectKey.extensionOf("pic.jpg"))
        assertEquals("bin", MediaObjectKey.extensionOf("pic"))
        assertEquals("bin", MediaObjectKey.extensionOf(""))
    }

    @Test
    fun `an extension that is really a path is refused`() {
        // A file whose name has a dot in the directory part must not turn into a
        // nested key: the policy reads the first folder, and a second one would
        // move the object out of the account's own folder.
        assertEquals("bin", MediaObjectKey.extensionOf("/storage/emulated/0/some.dir/file"))
        assertEquals("jpg", MediaObjectKey.extensionOf("/storage/emulated/0/pic.jpg"))
    }

    // --- helpers ---

    private suspend fun attach(optIn: Boolean, storagePath: String? = null) {
        val file = File(temporaryFolder.root, "captured.jpg")
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        db.mediaDao().upsert(
            MediaEntity(
                id = "media-1",
                ownerType = "MEMORY",
                ownerId = memoryId,
                mediaType = "PHOTO",
                uri = file.absolutePath,
                mimeType = "image/jpeg",
                createdAt = stamp,
                updatedAt = stamp,
                syncStatus = if (optIn) SyncStatus.PENDING_CREATE.name else SyncStatus.SYNCED.name,
                storagePath = storagePath,
                uploadRequested = optIn,
            ),
        )
    }

    private fun record(
        id: String,
        storagePath: String,
        deletedAt: String? = null,
    ) = MediaRecord(
        id = id,
        userId = userId,
        ownerType = "MEMORY",
        ownerId = memoryId,
        mediaType = "PHOTO",
        storagePath = storagePath,
        mimeType = "image/jpeg",
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-02-01T00:00:00Z",
        deletedAt = deletedAt,
    )
}
