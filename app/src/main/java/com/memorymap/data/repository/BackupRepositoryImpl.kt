package com.memorymap.data.repository

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.memorymap.BuildConfig
import com.memorymap.data.local.Mappers
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
import kotlinx.serialization.json.Json

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
    private val json: Json,
    private val memoryDao: MemoryDao,
    private val dailyEntryDao: DailyEntryDao,
    private val personDao: PersonDao,
    private val placeDao: PlaceDao,
    private val mediaDao: MediaDao,
) : BackupRepository {

    private val archive = BackupArchive(context, json)

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

            // The manifest is written last: an archive holding one has everything
            // else in it, so a half-written folder is recognised as unusable.
            val written = with(BackupMappers) {
                archive.writeMemories(root, memories.map { it.toBackup() }) &&
                    archive.writeEntries(root, entries.map { it.toBackup() }) &&
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
                        personDao.upsert(row.toEntity())
                        peopleRestored++
                    } else {
                        skipped++
                    }
                }
                archive.readPlaces(root).forEach { row ->
                    if (placeDao.getById(row.id) == null) {
                        placeDao.upsert(row.toEntity())
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
                            memoryDao.upsert(row.toEntity())
                            memoriesRestored++
                        }

                        BackupMerge.Decision.UPDATE -> {
                            memoryDao.upsert(row.toEntity(SyncStatus.PENDING_UPDATE))
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
                            dailyEntryDao.upsert(row.toEntity())
                            entriesRestored++
                        }

                        BackupMerge.Decision.UPDATE -> {
                            dailyEntryDao.upsert(row.toEntity(SyncStatus.PENDING_UPDATE))
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
