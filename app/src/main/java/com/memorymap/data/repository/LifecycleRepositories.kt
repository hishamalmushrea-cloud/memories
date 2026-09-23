package com.memorymap.data.repository

import com.memorymap.data.local.Mappers.toDomain
import com.memorymap.data.local.Mappers.toEntity
import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.dao.DiaryNoteDao
import com.memorymap.data.local.dao.MediaDao
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.data.local.dao.PersonDao
import com.memorymap.data.local.dao.PlaceDao
import com.memorymap.data.local.dao.UserDao
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.LifeStats
import com.memorymap.domain.model.MonthCount
import com.memorymap.domain.model.OnThisDayItem
import com.memorymap.domain.model.User
import com.memorymap.domain.repository.OnThisDayRepository
import com.memorymap.domain.repository.UserRepository
import com.memorymap.domain.usecase.DiaryTime
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val userDao: UserDao,
) : UserRepository {

    override fun watchCurrentUser(): Flow<User?> = userDao.watchFirst().map { it?.toDomain() }

    override suspend fun getById(id: String): User? = userDao.getById(id)?.toDomain()

    override suspend fun save(user: User) = userDao.upsert(user.toEntity())

    override suspend fun clearLocal() = userDao.deleteAll()
}

@Singleton
class OnThisDayRepositoryImpl @Inject constructor(
    private val memoryDao: MemoryDao,
    private val noteDao: DiaryNoteDao,
) : OnThisDayRepository {

    /**
     * Plain calendar matching, most recent year first. Nothing here is generated:
     * it only re-surfaces what the user already wrote on this day in earlier years.
     */
    override suspend fun items(userId: String, day: LocalDate): List<OnThisDayItem> {
        val pattern = DiaryTime.sameMonthDayPattern(day)
        val memories = memoryDao.onThisDay(userId, pattern).map { entity ->
            OnThisDayItem(
                year = entity.memoryDate.take(4).toIntOrNull() ?: 0,
                title = entity.title,
                isMemory = true,
            )
        }
        val notes = noteDao.onThisDay(userId, pattern).map { entity ->
            OnThisDayItem(
                year = entity.date.take(4).toIntOrNull() ?: 0,
                title = entity.text.lineSequence().firstOrNull { it.isNotBlank() } ?: "",
                isMemory = false,
            )
        }
        return (memories + notes).sortedByDescending { it.year }
    }
}

/**
 * Life statistics for the profile screen. Every number is counted from stored
 * rows; the "top emotion" and "most active month" are just the largest groups.
 */
@Singleton
class LifeStatsCalculator @Inject constructor(
    private val memoryDao: MemoryDao,
    private val entryDao: DailyEntryDao,
    private val mediaDao: MediaDao,
    private val placeDao: PlaceDao,
    private val personDao: PersonDao,
) {

    suspend fun calculate(userId: String): LifeStats {
        val mediaByType = mediaDao.countByType().associate { it.mediaType to it.count }
        val topEmotion = memoryDao.emotionDistribution(userId).firstOrNull()?.emotion?.let(Emotion::fromName)
        val topMonth = memoryDao.topMonths(userId).firstOrNull()?.let { row ->
            // `month` is `yyyy-MM` because that is what substr(memory_date, 1, 7) yields.
            val year = row.month.take(4).toIntOrNull()
            val month = row.month.drop(5).take(2).toIntOrNull()
            if (year != null && month != null) MonthCount(year, month, row.count) else null
        }

        return LifeStats(
            recordedDays = entryDao.countRecordedDays(userId),
            memories = memoryDao.count(userId),
            events = entryDao.count(userId),
            places = placeDao.count(userId),
            photos = mediaByType["PHOTO"] ?: 0,
            audio = mediaByType["AUDIO"] ?: 0,
            videos = mediaByType["VIDEO"] ?: 0,
            topEmotion = topEmotion,
            topMonth = topMonth,
        )
    }

    /** People count is exposed separately because the profile lists it too. */
    suspend fun peopleCount(userId: String): Int = personDao.count(userId)
}
