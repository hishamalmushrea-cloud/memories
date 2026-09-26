package com.memorymap.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.SyncStatus
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Attachments of a memory.
 *
 * The point of these tests is the delete path: an attachment removed offline must
 * leave a tombstone so the next sync replays the delete, and its bytes must be
 * gone from app-private storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaRepositoryImplTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: MemoryMapDatabase
    private lateinit var repository: MediaRepositoryImpl

    private val memoryId = "memory-1"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = MediaRepositoryImpl(db.mediaDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `an attachment starts out local and can be asked for`() = runTest {
        val item = item(MediaType.PHOTO, file("a.jpg"))
        repository.attach(item)
        assertFalse(repository.getFor(MediaOwner.MEMORY, memoryId).single().uploadRequested)

        repository.requestUpload(item.id)

        val asked = repository.getFor(MediaOwner.MEMORY, memoryId).single()
        assertTrue(asked.uploadRequested)
        // Queued for the next run rather than only flagged, or the worker would
        // never look at it.
        assertEquals(SyncStatus.PENDING_UPDATE, asked.syncStatus)
    }

    @Test
    fun `the request can be withdrawn while the bytes are still only here`() = runTest {
        val item = item(MediaType.PHOTO, file("a.jpg"))
        repository.attach(item)
        repository.requestUpload(item.id)

        repository.cancelUpload(item.id)

        val cancelled = repository.getFor(MediaOwner.MEMORY, memoryId).single()
        assertFalse(cancelled.uploadRequested)
        assertFalse(cancelled.isUploaded)
    }

    @Test
    fun `the request cannot be withdrawn once the bytes are in the cloud`() = runTest {
        val item = item(MediaType.PHOTO, file("a.jpg"))
        repository.attach(item)
        repository.requestUpload(item.id)
        // As the sync worker leaves it once the bytes are in the bucket.
        db.mediaDao().setStoragePath(item.id, "user-1/${item.id}.jpg", "2024-06-01T00:00:00")

        repository.cancelUpload(item.id)

        // Clearing it would leave an object in the bucket that nothing on the
        // server points at. Removing the attachment is the way to delete it.
        val still = repository.getFor(MediaOwner.MEMORY, memoryId).single()
        assertTrue(still.isUploaded)
        assertTrue(still.uploadRequested)
    }

    @Test
    fun `an attachment is stored and read back for its owner only`() = runTest {
        repository.attach(item(MediaType.PHOTO, file("a.jpg")))
        repository.attach(item(MediaType.PHOTO, file("b.jpg")))

        val mine = repository.getFor(MediaOwner.MEMORY, memoryId)
        val other = repository.getFor(MediaOwner.MEMORY, "another-memory")

        assertEquals(2, mine.size)
        assertTrue(other.isEmpty())
    }

    @Test
    fun `removing an attachment leaves a tombstone and deletes the file`() = runTest {
        val target = file("doomed.jpg")
        val attachment = item(MediaType.PHOTO, target)
        repository.attach(attachment)

        repository.remove(attachment.id)

        // Gone from every live read...
        assertTrue(repository.getFor(MediaOwner.MEMORY, memoryId).isEmpty())
        // ...but the row survives as a tombstone the sync worker can replay.
        val tombstones = db.mediaDao().tombstones()
        assertEquals(1, tombstones.size)
        assertEquals(SyncStatus.PENDING_DELETE.name, tombstones.single().syncStatus)
        // ...and the bytes are actually gone.
        assertFalse(target.exists())
    }

    @Test
    fun `removing every attachment of a memory clears the whole set`() = runTest {
        val first = file("1.jpg")
        val second = file("2.m4a")
        repository.attach(item(MediaType.PHOTO, first))
        repository.attach(item(MediaType.AUDIO, second))

        repository.removeAllFor(MediaOwner.MEMORY, memoryId)

        assertTrue(repository.getFor(MediaOwner.MEMORY, memoryId).isEmpty())
        assertEquals(2, db.mediaDao().tombstones().size)
        assertFalse(first.exists())
        assertFalse(second.exists())
    }

    @Test
    fun `counts by type ignore tombstones`() = runTest {
        val photo = item(MediaType.PHOTO, file("keep.jpg"))
        repository.attach(photo)
        repository.attach(item(MediaType.PHOTO, file("drop.jpg")))
        repository.attach(item(MediaType.AUDIO, file("voice.m4a")))
        repository.attach(item(MediaType.VIDEO, file("clip.mp4")))

        repository.remove(
            repository.getFor(MediaOwner.MEMORY, memoryId).single { it.uri.endsWith("drop.jpg") }.id,
        )

        val counts = repository.countByType()
        assertEquals(1, counts[MediaType.PHOTO])
        assertEquals(1, counts[MediaType.AUDIO])
        assertEquals(1, counts[MediaType.VIDEO])
        // Sanity: the surviving photo is the one that was kept.
        assertEquals(photo.uri, repository.getFor(MediaOwner.MEMORY, memoryId).single { it.type == MediaType.PHOTO }.uri)
    }

    @Test
    fun `the summary gives per owner counters and a cover photo`() = runTest {
        repository.attach(item(MediaType.PHOTO, file("2026_first.jpg")))
        repository.attach(item(MediaType.PHOTO, file("2026_second.jpg")))
        repository.attach(item(MediaType.AUDIO, file("voice.m4a")))

        val summary = repository.watchSummary(MediaOwner.MEMORY).first()

        val mine = summary.getValue(memoryId)
        assertEquals(2, mine.photos)
        assertEquals(1, mine.audio)
        assertEquals(0, mine.videos)
        assertEquals(3, mine.total)
        // File names embed a timestamp, so the smallest name is the earliest photo.
        assertTrue(mine.coverUri!!.endsWith("2026_first.jpg"))
    }

    @Test
    fun `discarding an unsaved attachment deletes the file without writing a row`() = runTest {
        val target = file("abandoned.jpg")
        val unsaved = item(MediaType.PHOTO, target)

        repository.discard(unsaved)

        assertFalse(target.exists())
        assertTrue(db.mediaDao().tombstones().isEmpty())
        assertTrue(repository.getFor(MediaOwner.MEMORY, memoryId).isEmpty())
    }

    @Test
    fun `the live stream updates after an attachment is added`() = runTest {
        repository.attach(item(MediaType.VIDEO, file("clip.mp4")))
        assertEquals(1, repository.watchFor(MediaOwner.MEMORY, memoryId).first().size)
    }

    /** A real file on disk, so the delete assertions mean something. */
    /**
     * A real file on disk, so the delete assertions mean something. The name is
     * used verbatim: the cover pick is `MIN(uri)`, which only selects the
     * earliest photo because real names embed a timestamp after a constant
     * owner prefix. A random prefix here would break that ordering.
     */
    private fun file(name: String): File =
        temporaryFolder.newFile(name).apply { writeText("x") }

    private fun item(type: MediaType, file: File) = MediaItem(
        ownerId = memoryId,
        ownerType = MediaOwner.MEMORY,
        type = type,
        uri = file.absolutePath,
        mimeType = when (type) {
            MediaType.PHOTO -> "image/jpeg"
            MediaType.AUDIO -> "audio/mp4"
            MediaType.VIDEO -> "video/mp4"
        },
    )
}
