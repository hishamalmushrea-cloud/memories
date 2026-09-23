package com.memorymap.data.repository

import com.memorymap.data.local.Mappers.toDomain
import com.memorymap.data.local.Mappers.toEntity
import com.memorymap.data.local.dao.MediaDao
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaSummary
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.repository.MediaRepository
import com.memorymap.util.MediaStore
import com.memorymap.util.MmLog
import java.io.File
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed attachment repository.
 *
 * Deleting an attachment keeps a tombstone row and removes the file, so an
 * offline delete is never resurrected by a later sync.
 */
@Singleton
class MediaRepositoryImpl @Inject constructor(
    private val mediaDao: MediaDao,
) : MediaRepository {

    override fun watchFor(owner: MediaOwner, ownerId: String): Flow<List<MediaItem>> =
        mediaDao.watchFor(owner.name, ownerId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getFor(owner: MediaOwner, ownerId: String): List<MediaItem> =
        mediaDao.getFor(owner.name, ownerId).map { it.toDomain() }

    override suspend fun attach(item: MediaItem) {
        mediaDao.upsert(item.toEntity())
    }

    override suspend fun remove(id: String) {
        val existing = mediaDao.getById(id)
        mediaDao.softDelete(id, LocalDateTime.now().toString())
        // The row survives as a tombstone; the bytes do not.
        existing?.let { deleteFile(it.uri) }
    }

    override suspend fun removeAllFor(owner: MediaOwner, ownerId: String) {
        val files = mediaDao.getFor(owner.name, ownerId).map { it.uri }
        mediaDao.softDeleteFor(owner.name, ownerId, LocalDateTime.now().toString())
        files.forEach { deleteFile(it) }
    }

    override suspend fun countByType(): Map<MediaType, Int> =
        mediaDao.countByType().mapNotNull { row ->
            MediaType.fromName(row.mediaType)?.let { it to row.count }
        }.toMap()

    override fun watchSummary(owner: MediaOwner): Flow<Map<String, MediaSummary>> =
        mediaDao.watchSummary(owner.name).map { rows ->
            rows.associate { row ->
                row.ownerId to MediaSummary(
                    photos = row.photos,
                    audio = row.audio,
                    videos = row.videos,
                    coverUri = row.coverUri,
                )
            }
        }

    override suspend fun discard(item: MediaItem) {
        deleteFile(item.uri)
    }

    /** Attachment URIs are app-private file paths, so the file is removed directly. */
    private fun deleteFile(uri: String) {
        runCatching { MediaStore.delete(File(uri)) }
            .onFailure { MmLog.e("Unable to delete the attachment file", it) }
    }
}
