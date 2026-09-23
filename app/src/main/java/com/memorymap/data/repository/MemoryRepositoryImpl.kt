package com.memorymap.data.repository

import com.memorymap.data.local.Mappers.toDomain
import com.memorymap.data.local.Mappers.toEntity
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.SyncStatus
import com.memorymap.domain.repository.MemoryRepository
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed memory repository. Every write is local first and is marked as
 * pending so the sync worker can pick it up whenever a connection exists.
 */
@Singleton
class MemoryRepositoryImpl @Inject constructor(
    private val memoryDao: MemoryDao,
) : MemoryRepository {

    override fun watchAll(userId: String): Flow<List<Memory>> =
        memoryDao.watchAll(userId).map { rows -> rows.map { it.toDomain() } }

    override fun watchLocated(userId: String): Flow<List<Memory>> =
        memoryDao.watchLocated(userId).map { rows -> rows.map { it.toDomain() } }

    override fun watchByDate(userId: String, date: LocalDate): Flow<List<Memory>> =
        memoryDao.watchByDate(userId, date.toString()).map { rows -> rows.map { it.toDomain() } }

    override suspend fun getById(id: String): Memory? = memoryDao.getById(id)?.toDomain()

    override suspend fun save(memory: Memory, personIds: List<String>, placeIds: List<String>) {
        val existing = memoryDao.getById(memory.id)
        val toWrite = memory.copy(
            createdAt = existing?.createdAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: memory.createdAt,
            updatedAt = LocalDateTime.now(),
            // A record that has never reached the server stays PENDING_CREATE,
            // otherwise the sync worker would try to PATCH a row that is not there.
            syncStatus = if (existing == null) SyncStatus.PENDING_CREATE else SyncStatus.PENDING_UPDATE,
        )
        memoryDao.upsert(toWrite.toEntity())
        memoryDao.replacePeople(memory.id, personIds)
        memoryDao.replacePlaces(memory.id, placeIds)
    }

    override suspend fun delete(id: String) {
        // Soft delete only: the tombstone is what tells the server to remove its
        // copy, so a memory deleted offline stays deleted after reconnecting.
        memoryDao.softDelete(id, LocalDateTime.now().toString())
    }

    override suspend fun search(userId: String, query: String): List<Memory> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return memoryDao.search(userId, trimmed).map { it.toDomain() }
    }

    override suspend fun count(userId: String): Int = memoryDao.count(userId)
}
