package com.memorymap.data.repository

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.MediaEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.SyncStatus
import com.memorymap.domain.repository.BackupOutcome
import com.memorymap.util.MediaStore
import com.memorymap.util.backup.BackupArchive
import com.memorymap.util.backup.BackupLayout
import com.memorymap.util.backup.MemoryBackup
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A backup written on one phone, read on another one.
 *
 * Every backup test so far restored into the same database it exported from, or
 * into the same instance with the rows deleted, and neither is the situation the
 * feature exists for: the phone is lost, the archive is not, and a new install
 * reads it. Two real databases on one machine are the closest thing to two phones
 * that this can run on - separate Room instances, separate files on disk, nothing
 * shared but the folder the user picked.
 *
 * What has to hold: the content arrives, the attachments arrive with their bytes,
 * the links between records arrive (a memory that comes back without the people
 * in it would be pushed to the server with no links and clear the ones the server
 * still holds), and every restored row is queued for upload rather than claiming
 * to be one the server already has. A fresh install has synced nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTwoDeviceTest {

    private lateinit var context: Context
    private lateinit var first: MemoryMapDatabase
    private lateinit var second: MemoryMapDatabase
    private lateinit var archiveDir: File
    private lateinit var firstPhone: BackupRepositoryImpl
    private lateinit var secondPhone: BackupRepositoryImpl

    private val userId = "user-1"
    private val treeUri = "content://unused"
    private val stamp = "2026-09-20T10:00:00Z"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        first = database()
        second = database()
        archiveDir = File(context.cacheDir, "two-device-${System.nanoTime()}").apply { mkdirs() }
        firstPhone = phone(first)
        secondPhone = phone(second)
    }

    @After
    fun tearDown() {
        first.close()
        second.close()
        archiveDir.deleteRecursively()
    }

    @Test
    fun `the second phone gets the content, the links and the attachments`() = runTest {
        seedTheFirstPhone()
        val exported = firstPhone.export(userId, treeUri) as BackupOutcome.Exported
        assertEquals("the export must have written the archive", 1, exported.mediaCopied)

        val restored = secondPhone.import(userId, treeUri) as BackupOutcome.Imported

        assertEquals(exported.manifest.counts.memories, restored.counts.memories)
        assertEquals(exported.manifest.counts.dailyEntries, restored.counts.dailyEntries)
        assertEquals(exported.manifest.counts.people, restored.counts.people)
        assertEquals(exported.manifest.counts.places, restored.counts.places)
        assertEquals(1, restored.mediaRestored)

        // The memory itself, field by field: a coordinate and an emotion are the
        // parts a person would notice missing.
        val memory = second.memoryDao().getById("m1")!!
        assertEquals("رحلة إلى إب", memory.title)
        assertEquals("كان الجو باردًا", memory.text)
        assertEquals(13.9667, memory.latitude!!, 0.0001)
        assertEquals(44.1833, memory.longitude!!, 0.0001)
        assertEquals("إب", memory.placeName)
        assertEquals("2026-09-01", memory.memoryDate)
        assertEquals("HAPPY", memory.emotion)
        assertEquals("SHARED", memory.visibility)

        // The links, which are what makes a restored archive the same archive.
        // Compared as sets: a join table has no order, and asserting one would
        // make the test depend on insertion order rather than on the links.
        assertEquals(setOf("p1", "p2"), second.memoryDao().peopleOf("m1").toSet())
        assertEquals(setOf("pl1"), second.memoryDao().placesOf("m1").toSet())
        assertEquals(setOf("p1"), second.dailyEntryDao().peopleOf("e1").toSet())

        // The second memory had no location and no links; it comes back with none.
        assertTrue(second.memoryDao().peopleOf("m2").isEmpty())
        assertEquals(null, second.memoryDao().getById("m2")!!.latitude)

        // The attachment's bytes, in a file that exists on this phone.
        val media = second.mediaDao().getById("media-1")!!
        assertEquals("m1", media.ownerId)
        val restoredFile = File(media.uri)
        assertTrue("the attachment must be on disk at ${media.uri}", restoredFile.exists())
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6), restoredFile.readBytes().toList())
    }

    @Test
    fun `everything restored is queued for upload, because this phone has synced nothing`() = runTest {
        seedTheFirstPhone()
        firstPhone.export(userId, treeUri)

        secondPhone.import(userId, treeUri)

        // PENDING_CREATE, not SYNCED: the archive carries content, not this
        // device's relationship with a server, and claiming SYNCED here would
        // mean the records never reach the account they are restored into.
        val statuses = second.memoryDao().allForUser(userId).map { it.syncStatus }.toSet()
        assertEquals(setOf(SyncStatus.PENDING_CREATE.name), statuses)
        assertEquals(
            listOf("e1"),
            second.dailyEntryDao().allForUser(userId).map { it.id },
        )
        assertEquals(
            setOf(SyncStatus.PENDING_CREATE.name),
            second.dailyEntryDao().allForUser(userId).map { it.syncStatus }.toSet(),
        )
        val queued = second.memoryDao().pendingSync().map { it.id }.sorted()
        assertEquals(listOf("m1", "m2"), queued)
    }

    @Test
    fun `restoring the same archive twice changes nothing the second time`() = runTest {
        seedTheFirstPhone()
        firstPhone.export(userId, treeUri)
        secondPhone.import(userId, treeUri)

        val again = secondPhone.import(userId, treeUri) as BackupOutcome.Imported

        assertEquals(0, again.counts.memories)
        assertEquals(0, again.counts.dailyEntries)
        assertEquals(0, again.mediaRestored)
        // Two people, one place, two memories, one entry and the attachment:
        // every one of them was already there, and nothing was overwritten.
        assertEquals("nothing was new, so everything was skipped", 7, again.skipped)
        // And the links are still there, not cleared by the second pass.
        assertEquals(setOf("p1", "p2"), second.memoryDao().peopleOf("m1").toSet())
    }

    @Test
    fun `a link removed on the second phone is not put back by the archive`() = runTest {
        seedTheFirstPhone()
        firstPhone.export(userId, treeUri)
        secondPhone.import(userId, treeUri)
        // The user takes a person out of the memory on the new phone.
        second.memoryDao().replacePeople("m1", listOf("p1"))

        secondPhone.import(userId, treeUri)

        // The archive is not newer than the row, so the row is skipped and its
        // links are left exactly as this phone has them.
        assertEquals(setOf("p1"), second.memoryDao().peopleOf("m1").toSet())
    }

    @Test
    fun `an archive whose link names a record it does not carry still imports`() = runTest {
        seedTheFirstPhone()
        firstPhone.export(userId, treeUri)
        // A person deleted on the exporting phone between the people document and
        // the memories document leaves a link with no person behind it. The two
        // documents are written one after the other, so this is a race the format
        // can produce, and it must not turn into an import that fails with a
        // constraint error the user can do nothing about.
        // Rewritten through the format's own serialiser rather than by editing the
        // text, so the test cannot pass or fail on how the writer spaces a colon.
        val memories = File(archiveDir, BackupLayout.MEMORIES)
        val rows = json.decodeFromString<List<MemoryBackup>>(memories.readText())
        memories.writeText(
            json.encodeToString(
                rows.map { if (it.id == "m1") it.copy(personIds = it.personIds + "p-gone") else it },
            ),
        )
        assertTrue("the tampered link must be in the archive", memories.readText().contains("p-gone"))

        val restored = secondPhone.import(userId, treeUri) as BackupOutcome.Imported

        assertEquals(2, restored.counts.memories)
        // The two people who are in the archive are linked; the one who is not is
        // dropped rather than invented.
        assertEquals(setOf("p1", "p2"), second.memoryDao().peopleOf("m1").toSet())
        assertNull("no person is invented for the missing id", second.personDao().getById("p-gone"))
    }

    /**
     * The archive was written by one account and is being restored into another.
     *
     * Restoring used to copy the exporting account's id into every row. The
     * result looked like a successful import - the counts were right - and left
     * the person with nothing: every row was filed under an id the app never asks
     * for, so the memories were invisible, and each one was queued for upload
     * under an id the server refuses, so none of them could ever sync. The bytes
     * belong to whoever is holding the archive now.
     */
    @Test
    fun `an archive from another account is restored into this one`() = runTest {
        val other = "user-2"
        seedTheFirstPhone()
        firstPhone.export(userId, treeUri)

        val restored = secondPhone.import(other, treeUri) as BackupOutcome.Imported

        assertEquals(2, restored.counts.memories)
        assertEquals(1, restored.counts.dailyEntries)
        assertEquals(1, restored.mediaRestored)

        // Visible to the account that restored them...
        assertEquals(
            setOf("m1", "m2"),
            second.memoryDao().allForUser(other).map { it.id }.toSet(),
        )
        assertEquals(
            setOf("e1"),
            second.dailyEntryDao().allForUser(other).map { it.id }.toSet(),
        )
        // ...and not also filed under the account that wrote them.
        assertTrue(second.memoryDao().allForUser(userId).isEmpty())
        assertEquals(other, second.memoryDao().getById("m1")!!.userId)
        assertEquals(other, second.dailyEntryDao().getById("e1")!!.userId)
        assertEquals(other, second.personDao().getById("p1")!!.userId)
        assertEquals(other, second.placeDao().getById("pl1")!!.userId)
    }

    private fun database(): MemoryMapDatabase =
        Room.inMemoryDatabaseBuilder(context, MemoryMapDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun phone(db: MemoryMapDatabase) = BackupRepositoryImpl(
        context = context,
        memoryDao = db.memoryDao(),
        dailyEntryDao = db.dailyEntryDao(),
        personDao = db.personDao(),
        placeDao = db.placeDao(),
        mediaDao = db.mediaDao(),
        archive = DirectoryArchive(context, json, archiveDir),
    )

    /**
     * One person's archive: two memories, one day with one event, two people, one
     * place, a photo on the first memory and links between them.
     */
    private suspend fun seedTheFirstPhone() {
        first.memoryDao().upsert(
            MemoryEntity(
                id = "m1",
                userId = userId,
                title = "رحلة إلى إب",
                text = "كان الجو باردًا",
                latitude = 13.9667,
                longitude = 44.1833,
                placeName = "إب",
                memoryDate = "2026-09-01",
                emotion = "HAPPY",
                visibility = "SHARED",
                createdAt = stamp,
                updatedAt = stamp,
                syncStatus = SyncStatus.SYNCED.name,
            ),
        )
        first.memoryDao().upsert(
            MemoryEntity(
                id = "m2",
                userId = userId,
                title = "بلا مكان",
                text = "",
                memoryDate = "2026-09-02",
                emotion = "NOSTALGIA",
                visibility = "PRIVATE",
                createdAt = stamp,
                updatedAt = stamp,
                syncStatus = SyncStatus.SYNCED.name,
            ),
        )
        first.dailyEntryDao().upsert(
            DailyEntryEntity(
                id = "e1",
                userId = userId,
                date = "2026-09-01",
                time = "18:30",
                title = "الغروب",
                text = "من سطح البيت",
                emotion = "HAPPY",
                createdAt = stamp,
                updatedAt = stamp,
                syncStatus = SyncStatus.SYNCED.name,
            ),
        )
        first.personDao().upsert(
            PersonEntity(
                id = "p1",
                userId = userId,
                name = "أحمد",
                createdAt = stamp,
                updatedAt = stamp,
                syncStatus = SyncStatus.SYNCED.name,
            ),
        )
        first.personDao().upsert(
            PersonEntity(
                id = "p2",
                userId = userId,
                name = "سعاد",
                createdAt = stamp,
                updatedAt = stamp,
                syncStatus = SyncStatus.SYNCED.name,
            ),
        )
        first.placeDao().upsert(
            PlaceEntity(
                id = "pl1",
                userId = userId,
                name = "إب",
                latitude = 13.9667,
                longitude = 44.1833,
                createdAt = stamp,
                updatedAt = stamp,
                syncStatus = SyncStatus.SYNCED.name,
            ),
        )

        // The links last: both sides of a join are foreign keys, and a link to a
        // person who is not in the database yet is refused by SQLite.
        first.memoryDao().replacePeople("m1", listOf("p1", "p2"))
        first.memoryDao().replacePlaces("m1", listOf("pl1"))
        first.dailyEntryDao().replacePeople("e1", listOf("p1"))
        first.dailyEntryDao().replacePlaces("e1", listOf("pl1"))

        val photo = File(MediaStore.dir(context, MediaType.PHOTO), "m1_photo.jpg")
        photo.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        first.mediaDao().upsert(
            MediaEntity(
                id = "media-1",
                ownerType = "MEMORY",
                ownerId = "m1",
                mediaType = MediaType.PHOTO.name,
                uri = photo.absolutePath,
                mimeType = "image/jpeg",
                createdAt = stamp,
                syncStatus = SyncStatus.SYNCED.name,
            ),
        )
        assertNotNull("the fixture must be in place", first.memoryDao().getById("m1"))
    }

    /** The one part of the archive that needs a real permission grant. */
    private class DirectoryArchive(
        context: Context,
        json: Json,
        private val dir: File,
    ) : BackupArchive(context, json) {
        override fun root(treeUri: String): DocumentFile = DocumentFile.fromFile(dir)
    }
}
