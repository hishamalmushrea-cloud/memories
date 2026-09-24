package com.memorymap.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.MediaEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.data.local.entities.SyncMetaEntity
import com.memorymap.data.local.entities.UserEntity
import com.memorymap.domain.model.MediaType
import com.memorymap.util.MediaStore
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Account deletion, the one action that has to leave nothing behind.
 *
 * A diary that keeps rows after the user asks for them to be destroyed is worse
 * than one that never offered the option, so this checks the whole surface: the
 * records, the attachment rows, the bytes on disk, the sync bookmark and the
 * account row itself — and that a second account is untouched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountDeletionTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var context: Context
    private lateinit var repository: UserRepositoryImpl

    private val userId = "user-1"
    private val otherUserId = "user-2"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MemoryMapDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = UserRepositoryImpl(
            context = context,
            userDao = db.userDao(),
            memoryDao = db.memoryDao(),
            entryDao = db.dailyEntryDao(),
            noteDao = db.diaryNoteDao(),
            personDao = db.personDao(),
            placeDao = db.placeDao(),
            mediaDao = db.mediaDao(),
            syncMetaDao = db.syncMetaDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
        MediaStore.clear(context)
    }

    private fun memory(id: String, owner: String) = MemoryEntity(
        id = id,
        userId = owner,
        title = "ذكرى $id",
        text = "نص",
        memoryDate = "2024-03-01",
        emotion = "HAPPY",
        visibility = "PRIVATE",
        createdAt = "2024-03-01T09:00:00",
        updatedAt = "2024-03-01T09:00:00",
        syncStatus = "SYNCED",
    )

    private fun entry(id: String, owner: String) = DailyEntryEntity(
        id = id,
        userId = owner,
        date = "2024-03-01",
        time = "21:40:00",
        title = "حدث $id",
        text = "نص",
        createdAt = "2024-03-01T21:40:00",
        updatedAt = "2024-03-01T21:40:00",
        syncStatus = "SYNCED",
    )

    /** A real file on disk plus the row that points at it. */
    private fun attachment(id: String, ownerId: String): MediaEntity {
        val file = File(MediaStore.dir(context, MediaType.PHOTO), "$ownerId-$id.jpg")
        file.writeText("pretend image bytes")
        return MediaEntity(
            id = id,
            ownerType = "MEMORY",
            ownerId = ownerId,
            mediaType = "PHOTO",
            uri = file.absolutePath,
            mimeType = "image/jpeg",
            createdAt = "2024-03-01T10:00:00",
            syncStatus = "SYNCED",
        )
    }

    private suspend fun seedArchive() {
        db.userDao().upsert(UserEntity(id = userId, email = "a@example.com", displayName = "أ", createdAt = "2024-01-01T00:00:00"))
        db.memoryDao().upsert(memory("m1", userId))
        db.memoryDao().upsert(memory("m2", userId))
        db.dailyEntryDao().upsert(entry("e1", userId))
        db.personDao().upsert(PersonEntity(id = "p1", userId = userId, name = "أحمد", createdAt = "2024-01-01T00:00:00"))
        db.placeDao().upsert(
            PlaceEntity(id = "pl1", userId = userId, name = "صنعاء", latitude = 15.36, longitude = 44.19, createdAt = "2024-01-01T00:00:00"),
        )
        db.mediaDao().upsert(attachment("media-1", "m1"))
        db.mediaDao().upsert(attachment("media-2", "m2"))
        db.syncMetaDao().upsert(SyncMetaEntity(userId = userId, lastRunOutcome = "ok"))
    }

    @Test
    fun `the summary reports what was there before it was destroyed`() = runTest {
        seedArchive()

        val summary = repository.deleteLocalData(userId)

        assertEquals(2, summary.memories)
        assertEquals(1, summary.entries)
        assertEquals(1, summary.people)
        assertEquals(1, summary.places)
        assertEquals(2, summary.mediaFiles)
        assertEquals(5, summary.totalRecords)
    }

    @Test
    fun `every row of the account is gone afterwards`() = runTest {
        seedArchive()

        repository.deleteLocalData(userId)

        assertEquals(0, db.memoryDao().count(userId))
        assertEquals(0, db.dailyEntryDao().count(userId))
        assertEquals(0, db.personDao().count(userId))
        assertEquals(0, db.placeDao().count(userId))
        assertEquals(null, db.userDao().getById(userId))
    }

    @Test
    fun `the attachment rows go with their records`() = runTest {
        seedArchive()

        repository.deleteLocalData(userId)

        // Media has no foreign key to a memory, so it needs an explicit delete;
        // without one these rows would survive as records nothing can display.
        assertTrue(db.mediaDao().getFor("MEMORY", "m1").isEmpty())
        assertTrue(db.mediaDao().getFor("MEMORY", "m2").isEmpty())
    }

    @Test
    fun `the media files themselves are deleted, not just the rows`() = runTest {
        seedArchive()
        val photoDir = MediaStore.dir(context, MediaType.PHOTO)
        assertTrue("the files should exist before the wipe", photoDir.listFiles().orEmpty().isNotEmpty())

        repository.deleteLocalData(userId)

        assertEquals(0L, MediaStore.usedBytes(context))
    }

    @Test
    fun `the sync bookmark goes too`() = runTest {
        seedArchive()

        repository.deleteLocalData(userId)

        // Left behind, the next sign-in would resume from a watermark belonging
        // to an archive that no longer exists.
        assertEquals(null, db.syncMetaDao().get(userId))
    }

    @Test
    fun `another account on the same device is untouched`() = runTest {
        seedArchive()
        db.userDao().upsert(UserEntity(id = otherUserId, email = "b@example.com", displayName = "ب", createdAt = "2024-01-01T00:00:00"))
        db.memoryDao().upsert(memory("other-m1", otherUserId))
        db.dailyEntryDao().upsert(entry("other-e1", otherUserId))

        repository.deleteLocalData(userId)

        assertEquals(1, db.memoryDao().count(otherUserId))
        assertEquals(1, db.dailyEntryDao().count(otherUserId))
        assertNotNull(db.memoryDao().getById("other-m1"))
    }

    @Test
    fun `wiping an archive that is already empty changes nothing and says so`() = runTest {
        db.userDao().upsert(UserEntity(id = userId, email = "a@example.com", displayName = "أ", createdAt = "2024-01-01T00:00:00"))

        val summary = repository.deleteLocalData(userId)

        assertTrue(summary.isEmpty)
        assertEquals(null, db.userDao().getById(userId))
    }

    @Test
    fun `a deleted memory is removed by the wipe even though it is not counted`() = runTest {
        seedArchive()
        // A tombstone is invisible to count() but still a row on disk, and the
        // wipe has to take it or it would be resurrected by a later sync.
        db.memoryDao().upsert(memory("m3", userId).copy(deletedAt = "2025-01-01T00:00:00"))
        assertEquals(2, db.memoryDao().count(userId))

        val summary = repository.deleteLocalData(userId)

        assertEquals("the summary counts what the user could see", 2, summary.memories)
        assertEquals(null, db.memoryDao().getById("m3"))
    }

    @Test
    fun `the media folder is recreated on demand after a wipe`() = runTest {
        seedArchive()
        repository.deleteLocalData(userId)
        assertFalse(File(MediaStore.dir(context, MediaType.PHOTO), "x.jpg").exists())

        // Deleting the archive must not leave the app unable to take a new photo.
        val fresh = MediaStore.newFile(context, MediaType.PHOTO, "m9", "jpg")
        assertTrue(fresh.parentFile?.exists() == true)
    }
}
