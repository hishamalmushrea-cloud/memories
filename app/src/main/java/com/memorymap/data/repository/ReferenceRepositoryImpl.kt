package com.memorymap.data.repository

import com.memorymap.data.local.Mappers.toDomain
import com.memorymap.data.local.Mappers.toEntity
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.dao.PersonDao
import com.memorymap.data.local.dao.PlaceDao
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.Person
import com.memorymap.domain.model.Place
import com.memorymap.domain.model.SyncStatus
import com.memorymap.domain.repository.ReferenceRepository
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ReferenceRepositoryImpl @Inject constructor(
    private val personDao: PersonDao,
    private val placeDao: PlaceDao,
    private val memoryDao: MemoryDao,
    private val entryDao: DailyEntryDao,
) : ReferenceRepository {

    override fun watchPeople(userId: String): Flow<List<Person>> =
        personDao.watchAll(userId).map { rows -> rows.map { it.toDomain() } }

    override fun watchPlaces(userId: String): Flow<List<Place>> =
        placeDao.watchAll(userId).map { rows -> rows.map { it.toDomain() } }

    /**
     * Typing a name in the event editor must not create duplicates, so an
     * existing person with the same name is reused.
     */
    override suspend fun findOrCreatePerson(userId: String, name: String): Person {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "A person needs a name" }
        val existing = personDao.findByNameIncludingDeleted(userId, trimmed)
        if (existing != null && existing.deletedAt == null) return existing.toDomain()

        val now = LocalDateTime.now()
        val created = Person(
            id = UUID.randomUUID().toString(),
            userId = userId,
            name = trimmed,
            createdAt = now,
        )
        val stamp = now.toString()
        personDao.upsert(
            // A revived name keeps its old id and creation time: the links other
            // records hold point at that id, and a fresh row would orphan them.
            PersonEntity(
                id = existing?.id ?: created.id,
                userId = created.userId,
                name = created.name,
                createdAt = existing?.createdAt ?: stamp,
                updatedAt = stamp,
                deletedAt = null,
                syncStatus = if (existing == null) {
                    SyncStatus.PENDING_CREATE.name
                } else {
                    SyncStatus.PENDING_UPDATE.name
                },
            ),
        )
        return if (existing == null) created else existing.toDomain().copy(name = created.name)
    }

    override suspend fun savePlace(place: Place) {
        val now = LocalDateTime.now().toString()
        val existing = placeDao.getById(place.id)
        placeDao.upsert(
            place.toEntity().copy(
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                syncStatus = if (existing == null) {
                    SyncStatus.PENDING_CREATE.name
                } else {
                    SyncStatus.PENDING_UPDATE.name
                },
            ),
        )
    }

    /**
     * Deletes as a tombstone rather than removing the row.
     *
     * A hard delete would simply never reach the server, so the name would
     * reappear on the next download and the deletion would look like it had
     * been ignored.
     */
    override suspend fun deletePerson(id: String) = personDao.softDelete(id, LocalDateTime.now().toString())

    override suspend fun deletePlace(id: String) = placeDao.softDelete(id, LocalDateTime.now().toString())

    override suspend fun entriesWithPerson(personId: String): List<DailyEntry> =
        entryDao.entriesWithPerson(personId).map { it.toDomain() }

    override suspend fun memoriesWithPerson(personId: String): List<Memory> =
        memoryDao.memoriesWithPerson(personId).map { it.toDomain() }

    override suspend fun entriesAtPlace(placeId: String): List<DailyEntry> =
        entryDao.entriesAtPlace(placeId).map { it.toDomain() }

    override suspend fun memoriesAtPlace(placeId: String): List<Memory> =
        memoryDao.memoriesAtPlace(placeId).map { it.toDomain() }
}
