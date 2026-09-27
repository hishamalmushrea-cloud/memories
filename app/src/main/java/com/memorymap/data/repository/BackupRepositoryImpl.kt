package com.memorymap.data.repository

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.memorymap.BuildConfig
import com.memorymap.data.local.Mappers
import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.dao.MediaDao
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.data.local.dao.PersonDao
import com.memorymap.data.local.dao.PlaceDao
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.SyncStatus
import com.memorymap.domain.repository.BackupOutcome
import com.memorymap.domain.repository.BackupRepository
import com.memorymap.domain.usecase.BackupMerge
import com.memorymap.util.MmLog
import com.memorymap.util.MediaStore
import com.memorymap.util.backup.BackupArchive
import com.memorymap.util.backup.BackupCounts
import com.memorymap.util.backup.EntryBackup
import com.memorymap.util.backup.BackupManifest
import com.memorymap.util.backup.BackupMappers
import com.memorymap.util.backup.BackupPlanner
import com.memorymap.util.backup.MediaBackup
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes and reads the local backup archive.
 *
 * An import merges. It never deletes a row the archive does not mention, and it
 * never overwrites a row this device has edited more recently — the same rule
 * sync uses, through the same [BackupMerge] decision. Restoring an old archive must
 * not be able to destroy newer work or resurrect something the user deleted.
 */
@Singleton
class BackupRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryDao: MemoryDao,
    private val dailyEntryDao: DailyEntryDao,
    private val personDao: PersonDao,
    private val placeDao: PlaceDao,
    private val mediaDao: MediaDao,
    private val archive: BackupArchive,
) : BackupRepository {

    override suspend fun export(userId: String, treeUri: String): BackupOutcome =
        withContext(Dispatchers.IO) {
            val root = archive.root(treeUri)
            if (root == null || !archive.canWrite(root)) {
                return@withContext BackupOutcome.Failed(if (root == null) "backup_error_open" else "backup_error_write")
            }

            val memories = memoryDao.allForUser(userId)
            val entries = dailyEntryDao.allForUser(userId)
            val people = personDao.allForUser(userId)
            val places = placeDao.allForUser(userId)

            val ownerIds = memories.map { it.id } + entries.map { it.id }
            val media = if (ownerIds.isEmpty()) {
                emptyList()
            } else {
                mediaDao.activeForOwners(ownerIds)
            }
            val mediaItems = with(Mappers) { media.map { it.toDomain() } }
            val paths = BackupPlanner.mediaArchivePaths(mediaItems)

            // Created even when there is nothing to put in it, so the folder on
            // disk matches the documented archive shape.
            val mediaDir = archive.mediaDir(root)
            if (mediaDir == null) return@withContext BackupOutcome.Failed("backup_error_write")

            val mediaRows = mutableListOf<MediaBackup>()
            var copied = 0
            var missing = 0
            if (media.isNotEmpty()) {
                for (row in media) {
                    val path = paths[row.id] ?: continue
                    if (archive.copyToArchive(File(row.uri), mediaDir, path.substringAfterLast('/'))) {
                        copied++
                        mediaRows += with(BackupMappers) { row.toBackup(path) }
                    } else {
                        // A record whose file is gone is still exported, minus its
                        // attachment. Losing the text because a photo vanished
                        // would be a worse outcome than the photo being absent.
                        missing++
                    }
                }
            }

            // Counted from what was actually written, not from what was looked
            // for: an attachment whose file had gone is absent from media.json,
            // and the manifest must not claim it.
            val archivedIds = mediaRows.map { it.id }.toSet()

            val manifest = BackupManifest(
                appVersion = BuildConfig.VERSION_NAME,
                createdAtEpochMs = System.currentTimeMillis(),
                counts = BackupPlanner.counts(
                    memories = memories.size,
                    dailyEntries = entries.size,
                    people = people.size,
                    places = places.size,
                    media = mediaItems.filter { it.id in archivedIds },
                ),
            )

            // The links are read here rather than inside the mapper, because they
            // live in their own tables and the mapper is pure.
            val memoryPeople = memories.associate { it.id to memoryDao.peopleOf(it.id) }
            val memoryPlaces = memories.associate { it.id to memoryDao.placesOf(it.id) }
            val entryPeople = entries.associate { it.id to dailyEntryDao.peopleOf(it.id) }
            val entryPlaces = entries.associate { it.id to dailyEntryDao.placesOf(it.id) }

            // The manifest is written last: an archive holding one has everything
            // else in it, so a half-written folder is recognised as unusable.
            val written = with(BackupMappers) {
                archive.writeMemories(
                    root,
                    memories.map { row ->
                        row.toBackup(
                            personIds = memoryPeople[row.id].orEmpty(),
                            placeIds = memoryPlaces[row.id].orEmpty(),
                        )
                    },
                ) &&
                    archive.writeEntries(
                        root,
                        entries.map { row ->
                            row.toBackup(
                                personIds = entryPeople[row.id].orEmpty(),
                                placeIds = entryPlaces[row.id].orEmpty(),
                            )
                        },
                    ) &&
                    archive.writePeople(root, people.map { it.toBackup() }) &&
                    archive.writePlaces(root, places.map { it.toBackup() }) &&
                    archive.writeMedia(root, mediaRows) &&
                    archive.writeManifest(root, manifest)
            }
            if (written) {
                BackupOutcome.Exported(manifest, copied, missing)
            } else {
                BackupOutcome.Failed("backup_error_write")
            }
        }

    override suspend fun inspect(treeUri: String): BackupManifest? = withContext(Dispatchers.IO) {
        val root = archive.root(treeUri) ?: return@withContext null
        archive.readManifest(root)
    }

    /**
     * Restores an archive into the account asking for it.
     *
     * [userId] is who owns everything afterwards, even when the archive was
     * written by a different account: see `MemoryBackup.toEntity`.
     */
    override suspend fun import(userId: String, treeUri: String): BackupOutcome =
        withContext(Dispatchers.IO) {
            val root = archive.root(treeUri)
            if (root == null || !root.exists()) return@withContext BackupOutcome.Failed("backup_error_open")

            val manifest = archive.readManifest(root)
            if (manifest == null) return@withContext BackupOutcome.Failed("backup_error_format")
            if (BackupPlanner.validate(manifest).isNotEmpty()) {
                return@withContext BackupOutcome.Failed("backup_error_format")
            }

            var skipped = 0
            var peopleRestored = 0
            var placesRestored = 0
            var memoriesRestored = 0
            var entriesRestored = 0

            with(BackupMappers) {
                archive.readPeople(root).forEach { row ->
                    if (personDao.getById(row.id) == null) {
                        personDao.upsert(row.toEntity(userId))
                        peopleRestored++
                    } else {
                        skipped++
                    }
                }
                archive.readPlaces(root).forEach { row ->
                    if (placeDao.getById(row.id) == null) {
                        placeDao.upsert(row.toEntity(userId))
                        placesRestored++
                    } else {
                        skipped++
                    }
                }
                archive.readMemories(root).forEach { row ->
                    val existing = memoryDao.getById(row.id)
                    val decision = BackupMerge.decide(
                        existing = existing?.let { BackupMerge.Existing(it.updatedAt, it.deletedAt) },
                        archivedUpdatedAt = row.updatedAt,
                    )
                    when (decision) {
                        BackupMerge.Decision.INSERT -> {
                            memoryDao.upsert(row.toEntity(userId))
                            restoreLinks(row.id, row.personIds, row.placeIds)
                            memoriesRestored++
                        }

                        BackupMerge.Decision.UPDATE -> {
                            memoryDao.upsert(row.toEntity(userId, SyncStatus.PENDING_UPDATE))
                            restoreLinks(row.id, row.personIds, row.placeIds)
                            memoriesRestored++
                        }

                        BackupMerge.Decision.SKIP -> skipped++
                    }
                }
                archive.readEntries(root).forEach { row ->
                    val existing = dailyEntryDao.getById(row.id)
                    val decision = BackupMerge.decide(
                        existing = existing?.let { BackupMerge.Existing(it.updatedAt, it.deletedAt) },
                        archivedUpdatedAt = row.updatedAt,
                    )
                    when (decision) {
                        BackupMerge.Decision.INSERT -> {
                            dailyEntryDao.upsert(entryFor(row, userId, SyncStatus.PENDING_CREATE))
                            dailyEntryDao.replacePeople(row.id, peoplePresent(row.personIds))
                            dailyEntryDao.replacePlaces(row.id, placesPresent(row.placeIds))
                            entriesRestored++
                        }

                        BackupMerge.Decision.UPDATE -> {
                            dailyEntryDao.upsert(entryFor(row, userId, SyncStatus.PENDING_UPDATE))
                            dailyEntryDao.replacePeople(row.id, peoplePresent(row.personIds))
                            dailyEntryDao.replacePlaces(row.id, placesPresent(row.placeIds))
                            entriesRestored++
                        }

                        BackupMerge.Decision.SKIP -> skipped++
                    }
                }
            }

            val mediaRestored = restoreMedia(root, userId) { skipped++ }

            return@withContext BackupOutcome.Imported(
                counts = BackupCounts(
                    memories = memoriesRestored,
                    dailyEntries = entriesRestored,
                    people = peopleRestored,
                    places = placesRestored,
                ),
                mediaRestored = mediaRestored,
                skipped = skipped,
            )
        }

    /**
     * Puts a restored memory's links back.
     *
     * Only on insert and update, never on skip: a row this device already holds a
     * newer copy of must not have its links rewritten by an older archive. An
     * archive written before the links were kept carries none, and replacing with
     * an empty list is then correct - there is nothing to restore.
     */
    private suspend fun restoreLinks(memoryId: String, personIds: List<String>, placeIds: List<String>) {
        memoryDao.replacePeople(memoryId, peoplePresent(personIds))
        memoryDao.replacePlaces(memoryId, placesPresent(placeIds))
    }

    /**
     * The links in an archive that this device can actually point at.
     *
     * The join tables are foreign keys, so a link to a person the archive does not
     * carry - a person deleted on the exporting phone between the export of the
     * people document and the export of the memories, which is possible because
     * the two are written one after the other - would fail the whole import with a
     * constraint error the user can do nothing about. The link is dropped instead:
     * the memory is what they are restoring.
     */
    private suspend fun peoplePresent(ids: List<String>): List<String> =
        ids.filter { personDao.getById(it) != null }

    private suspend fun placesPresent(ids: List<String>): List<String> =
        ids.filter { placeDao.getById(it) != null }

    /**
     * A diary event as this device can store it.
     *
     * An event points at a place by id, and that is a foreign key too: an archive
     * whose place is gone carries the id without the place. The event is kept and
     * the pointer dropped, because its text is what the user is restoring.
     */
    private suspend fun entryFor(
        row: EntryBackup,
        ownerId: String,
        status: SyncStatus,
    ): DailyEntryEntity {
        val entity = with(BackupMappers) { row.toEntity(ownerId, status) }
        val placeId = entity.placeId
        return if (placeId != null && placeDao.getById(placeId) == null) {
            entity.copy(placeId = null)
        } else {
            entity
        }
    }

    /**
     * Copies attachments out of the archive and re-links them to their record.
     *
     * An attachment whose owner is not on this device is left alone rather than
     * written as an orphan: a record can be restored later from the same
     * archive, and the file will still be there.
     */
    private suspend fun restoreMedia(
        root: DocumentFile,
        userId: String,
        onSkipped: () -> Unit,
    ): Int {
        val liveOwners = (memoryDao.allForUser(userId).map { it.id } +
            dailyEntryDao.allForUser(userId).map { it.id }).toSet()
        if (liveOwners.isEmpty()) return 0

        var restored = 0
        archive.readMedia(root).forEach { row ->
            when {
                row.ownerId !in liveOwners -> onSkipped()
                mediaDao.getById(row.id) != null -> onSkipped()
                else -> {
                    val source = archive.findMediaFile(root, row.archivePath)
                    val type = runCatching { MediaType.valueOf(row.mediaType) }.getOrNull()
                    if (source == null || type == null) {
                        onSkipped()
                        return@forEach
                    }
                    val extension = row.archivePath.substringAfterLast('.', "bin")
                    val target = MediaStore.newFile(context, type, row.ownerId, extension)
                    if (!archive.copyFromArchive(source, target)) {
                        MmLog.e("Could not restore a media file from the archive")
                        onSkipped()
                        return@forEach
                    }
                    mediaDao.upsert(with(BackupMappers) { row.toEntity(target.absolutePath) })
                    restored++
                }
            }
        }
        return restored
    }
}
