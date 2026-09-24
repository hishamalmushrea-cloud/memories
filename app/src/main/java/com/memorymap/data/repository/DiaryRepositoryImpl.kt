package com.memorymap.data.repository

import com.memorymap.data.local.Mappers.diaryNote
import com.memorymap.data.local.Mappers.toDomain
import com.memorymap.data.local.Mappers.toEntity
import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.dao.DayCountRow
import com.memorymap.data.local.dao.DiaryNoteDao
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.DayContentCounts
import com.memorymap.domain.model.SyncStatus
import com.memorymap.domain.repository.DiaryRepository
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class DiaryRepositoryImpl @Inject constructor(
    private val entryDao: DailyEntryDao,
    private val noteDao: DiaryNoteDao,
) : DiaryRepository {

    override fun watchDay(userId: String, date: LocalDate): Flow<List<DailyEntry>> =
        entryDao.watchByDate(userId, date.toString()).map { rows -> rows.map { it.toDomain() } }

    override fun watchRange(userId: String, from: LocalDate, to: LocalDate): Flow<List<DailyEntry>> =
        entryDao.watchBetween(userId, from.toString(), to.toString())
            .map { rows -> rows.map { it.toDomain() } }

    override fun watchDayCounts(userId: String, from: LocalDate, to: LocalDate): Flow<List<DayContentCounts>> =
        entryDao.watchDayCounts(userId, from.toString(), to.toString()).map { rows ->
            rows.map { it.toDomain() }
        }

    override suspend fun getEntry(id: String): DailyEntry? = entryDao.getById(id)?.toDomain()

    override suspend fun saveEntry(entry: DailyEntry, personIds: List<String>, placeIds: List<String>) {
        val existing = entryDao.getById(entry.id)
        val toWrite = entry.copy(
            createdAt = existing?.createdAt?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: entry.createdAt,
            updatedAt = LocalDateTime.now(),
            syncStatus = if (existing == null) SyncStatus.PENDING_CREATE else SyncStatus.PENDING_UPDATE,
        )
        entryDao.upsert(toWrite.toEntity())
        entryDao.clearPeople(entry.id)
        personIds.forEach { entryDao.linkPerson(com.memorymap.data.local.entities.DailyEntryPersonCrossRef(entry.id, it)) }
        entryDao.clearPlaces(entry.id)
        placeIds.forEach { entryDao.linkPlace(com.memorymap.data.local.entities.DailyEntryPlaceCrossRef(entry.id, it)) }
    }

    override suspend fun deleteEntry(id: String) {
        entryDao.softDelete(id, LocalDateTime.now().toString())
    }

    override suspend fun getDiaryNote(userId: String, date: LocalDate): String? =
        noteDao.get(userId, date.toString())?.text

    override suspend fun saveDiaryNote(userId: String, date: LocalDate, text: String) {
        noteDao.upsert(diaryNote(userId, date, text))
    }

    override suspend fun search(userId: String, query: String): List<DailyEntry> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()
        return entryDao.search(userId, trimmed).map { it.toDomain() }
    }
}

/** Folds one aggregation row into the model the week/month/year pages consume. */
private fun DayCountRow.toDomain(): DayContentCounts {
    val parsed = runCatching { LocalDate.parse(date) }.getOrNull()
    return DayContentCounts(
        date = parsed ?: LocalDate.now(),
        entries = entries,
        photos = photos,
        audio = audio,
        videos = videos,
        memories = memories,
        hasDiaryNote = hasDiaryNote > 0,
    )
}
