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
import com.memorymap.util.backup.BackupCounts
import com.memorymap.util.backup.BackupLayout
import com.memorymap.util.backup.BackupManifest
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Export and import against a real archive on disk.
 *
 * The Storage Access Framework itself is the one part that cannot run here, so
 * [DirectoryArchive] points the archive at an ordinary directory. Everything
 * else is the real code: the real serialisation, the real merge decision, the
 * real database. That is what makes these worth having - the rule an import has
 * to keep is that it can never destroy newer work or resurrect a deletion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupRepositoryImplTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var repository: BackupRepositoryImpl
    private lateinit var archiveDir: File
    private lateinit var appContext: Context

    private val userId = "user-1"
    private val otherUser = "user-2"
    private val treeUri = "content://unused"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        appContext = context
        db = Room.inMemoryDatabaseBuilder(context, MemoryMapDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        archiveDir = File(context.cacheDir, "archive-${System.nanoTime()}").apply { mkdirs() }
        repository = BackupRepositoryImpl(
            context = context,
            memoryDao = db.memoryDao(),
            dailyEntryDao = db.dailyEntryDao(),
            personDao = db.personDao(),
            placeDao = db.placeDao(),
            mediaDao = db.mediaDao(),
            archive = DirectoryArchive(context, json, archiveDir),
        )
    }

    @After
    fun tearDown() {
        db.close()
        archiveDir.deleteRecursively()
    }

    @Test
    fun `a document written through the archive keeps the name the format defines`() = runTest {
        val archive = DirectoryArchive(appContext, json, archiveDir)
        val root = archive.root(treeUri)
        val wrote = archive.writeManifest(
            root,
            BackupManifest(appVersion = "test", createdAtEpochMs = 1L, counts = BackupCounts(memories = 2)),
        )

        val contents = archiveDir.listFiles()?.map { it.name }?.sorted()
        val detail = "wrote=$wrote dirExists=${archiveDir.exists()} dir=${archiveDir.path} " +
            "contents=$contents rootUri=${root.uri} readBack=${archive.readManifest(root)}"
        assertTrue(detail, wrote)
        assertEquals(detail, listOf(BackupLayout.MANIFEST), contents)
    }

    @Test
    fun `an export writes every document the format promises`() = runTest {
        seedArchive()

        val outcome = repository.export(userId, treeUri)

        assertTrue(outcome.toString(), outcome is BackupOutcome.Exported)
        for (name in BackupLayout.JSON_FILES) {
            assertNotNull("missing $name", File(archiveDir, name).takeIf { it.exists() })
        }
        // Created even with no attachments in it, so the folder on disk always
        // matches the documented shape.
        assertTrue(File(archiveDir, BackupLayout.MEDIA_DIR).isDirectory)
    }

    @Test
    fun `an export writes the documents under the names the format defines`() = runTest {
        seedArchive()

        repository.export(userId, treeUri)

        // The reader looks documents up by these names, so a provider that
        // renames what it creates produces an archive that cannot be restored.
        val names = archiveDir.listFiles()?.map { it.name }?.toSet() ?: emptySet()
        assertTrue("archive holds $names", names.containsAll(BackupLayout.JSON_FILES))
    }

    @Test
    fun `an export counts only what it actually wrote`() = runTest {
        seedArchive()

        val outcome = repository.export(userId, treeUri) as BackupOutcome.Exported

        assertEquals(2, outcome.manifest.counts.memories)
        assertEquals(1, outcome.manifest.counts.dailyEntries)
        assertEquals(1, outcome.manifest.counts.people)
        assertEquals(1, outcome.manifest.counts.places)
    }

    @Test
    fun `an export leaves another account's records alone`() = runTest {
        seedArchive()
        db.memoryDao().upsert(memory("m-other", otherUser, "ذكرى شخص آخر"))

        val outcome = repository.export(userId, treeUri) as BackupOutcome.Exported

        // A backup is per account; leaking somebody else's rows into it would be
        // a privacy failure, not a counting one.
        assertEquals(2, outcome.manifest.counts.memories)
    }

    @Test
    fun `inspect reads the manifest without touching any data`() = runTest {
        seedArchive()
        repository.export(userId, treeUri)
        db.memoryDao().hardDeleteAll(userId)
        assertEquals(0, db.memoryDao().allForUser(userId).size)

        val manifest = repository.inspect(treeUri)

        assertNotNull(manifest)
        assertEquals(2, manifest!!.counts.memories)
        // Reading an archive must not restore anything.
        assertEquals(0, db.memoryDao().allForUser(userId).size)
    }

    @Test
    fun `inspecting a folder with no manifest reports nothing rather than failing loudly`() =
        runTest {
            assertNull(repository.inspect(treeUri))
        }

    @Test
    fun `an import into an empty database restores everything`() = runTest {
        seedArchive()
        repository.export(userId, treeUri)
        // A fresh device: the same archive, none of the records.
        wipe()

        val outcome = repository.import(userId, treeUri) as BackupOutcome.Imported

        assertEquals(2, outcome.counts.memories)
        assertEquals(1, outcome.counts.dailyEntries)
        assertEquals(1, outcome.counts.people)
        assertEquals(1, outcome.counts.places)
        assertEquals(2, db.memoryDao().allForUser(userId).size)
        assertEquals("أحمد", db.personDao().getById("p1")!!.name)
    }

    @Test
    fun `an import keeps a record this device has edited more recently`() = runTest {
        seedArchive()
        repository.export(userId, treeUri)
        // The archive holds the 2024 copy; this device rewrote the row in 2026.
        db.memoryDao().upsert(
            memory("m1", userId, "نسخة أحدث محليًا").copy(
                updatedAt = "2026-01-01T00:00:00",
                syncStatus = SyncStatus.PENDING_UPDATE.name,
            ),
        )

        val outcome = repository.import(userId, treeUri) as BackupOutcome.Imported

        assertEquals("نسخة أحدث محليًا", db.memoryDao().getById("m1")!!.title)
        // Nothing was replaced at all: this device already holds every row the
        // archive names, four of them in exactly the archived state and m1 newer.
        assertEquals(BackupCounts(), outcome.counts)
        assertEquals(5, outcome.skipped)
    }

    @Test
    fun `an import does not resurrect a record the user deleted`() = runTest {
        seedArchive()
        repository.export(userId, treeUri)
        db.memoryDao().softDelete("m1", "2026-01-01T00:00:00")

        repository.import(userId, treeUri)

        // The archive holds no tombstones, so only the local one can say the
        // record is gone. Honoured, the row stays deleted.
        assertNotNull(db.memoryDao().getById("m1")!!.deletedAt)
    }

    @Test
    fun `an import adds a record this device has never seen and leaves the rest`() = runTest {
        seedArchive()
        repository.export(userId, treeUri)
        // Only the memories are gone. The event, the person and the place are
        // still here, which is what makes this a partial restore.
        db.memoryDao().hardDeleteAll(userId)

        val outcome = repository.import(userId, treeUri) as BackupOutcome.Imported

        assertEquals(2, outcome.counts.memories)
        assertEquals("رحلة إلى إب", db.memoryDao().getById("m1")!!.title)
        // The three rows that were already here were not touched.
        assertEquals(3, outcome.skipped)
    }

    @Test
    fun `an attachment whose file is gone is exported without it, record intact`() = runTest {
        seedArchive()
        // Points at a path that does not exist, as a file the user removed would.
        db.mediaDao().upsert(
            MediaEntity(
                id = "media-missing",
                ownerType = "MEMORY",
                ownerId = "m1",
                mediaType = "PHOTO",
                uri = File(archiveDir, "never-existed.jpg").absolutePath,
                mimeType = "image/jpeg",
                createdAt = stamp,
                syncStatus = SyncStatus.SYNCED.name,
            ),
        )

        val outcome = repository.export(userId, treeUri) as BackupOutcome.Exported

        // Losing the text because a photo vanished would be the worse outcome.
        assertEquals(2, outcome.manifest.counts.memories)
        assertEquals(0, outcome.mediaCopied)
        assertEquals(1, outcome.mediaMissing)
    }

    @Test
    fun `an attachment survives a round trip through the archive`() = runTest {
        seedArchive()
        // A real file on this device, as an attachment would be.
        val original = File(MediaStore.dir(appContext, MediaType.PHOTO), "m1_original.jpg")
        original.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        db.mediaDao().upsert(
            MediaEntity(
                id = "media-1",
                ownerType = "MEMORY",
                ownerId = "m1",
                mediaType = MediaType.PHOTO.name,
                uri = original.absolutePath,
                mimeType = "image/jpeg",
                createdAt = stamp,
                syncStatus = SyncStatus.SYNCED.name,
            ),
        )

        val outcome = repository.export(userId, treeUri) as BackupOutcome.Exported

        assertEquals(1, outcome.mediaCopied)
        assertEquals(1, outcome.manifest.counts.photos)

        // The attachment and its file are gone from this device; the archive is not.
        db.mediaDao().deleteById("media-1")
        original.delete()
        assertFalse("the original should be gone", original.exists())

        val imported = repository.import(userId, treeUri) as BackupOutcome.Imported

        assertEquals(1, imported.mediaRestored)
        val restored = db.mediaDao().getById("media-1")
        assertNotNull("the row did not come back", restored)
        val file = File(restored!!.uri)
        assertTrue("${restored.uri} was not restored", file.exists())
        assertEquals(5L, file.length())
    }

    private suspend fun seedArchive() {
        db.personDao().upsert(PersonEntity(id = "p1", userId = userId, name = "أحمد", createdAt = stamp))
        db.placeDao().upsert(
            PlaceEntity(id = "pl1", userId = userId, name = "إب", latitude = 13.97, longitude = 44.17, createdAt = stamp),
        )
        db.memoryDao().upsert(memory("m1", userId, "رحلة إلى إب"))
        db.memoryDao().upsert(memory("m2", userId, "يوم في صنعاء"))
        db.dailyEntryDao().upsert(entry("e1", userId, "زيارة أحمد"))
    }

    private suspend fun wipe() {
        db.memoryDao().hardDeleteAll(userId)
        db.dailyEntryDao().hardDeleteAll(userId)
        db.personDao().deleteAll(userId)
        db.placeDao().deleteAll(userId)
    }

    private fun memory(id: String, owner: String, title: String) = MemoryEntity(
        id = id,
        userId = owner,
        title = title,
        text = "",
        memoryDate = "2024-01-01",
        emotion = "NOSTALGIA",
        visibility = "PRIVATE",
        createdAt = stamp,
        updatedAt = stamp,
        syncStatus = SyncStatus.SYNCED.name,
    )

    private fun entry(id: String, owner: String, title: String) = DailyEntryEntity(
        id = id,
        userId = owner,
        date = "2024-01-01",
        time = LocalDateTime.of(2024, 1, 1, 10, 0).toString(),
        title = title,
        text = "",
        createdAt = stamp,
        updatedAt = stamp,
        syncStatus = SyncStatus.SYNCED.name,
    )

    private val stamp = "2024-01-01T00:00:00"

    /**
     * The archive pointed at a real directory instead of a SAF tree.
     *
     * Only [root] is replaced. Everything below it - creating the folder, the
     * JSON documents, the copies - is the code under test.
     */
    private class DirectoryArchive(
        context: Context,
        json: Json,
        private val dir: File,
    ) : BackupArchive(context, json) {
        override fun root(treeUri: String): DocumentFile = DocumentFile.fromFile(dir)
    }
}
