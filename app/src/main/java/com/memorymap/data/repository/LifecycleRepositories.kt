package com.memorymap.data.repository

import android.content.Context
import com.memorymap.data.local.Mappers.toDomain
import com.memorymap.data.local.Mappers.toEntity
import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.dao.DiaryNoteDao
import com.memorymap.data.local.dao.MediaDao
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.data.local.dao.SyncMetaDao
import com.memorymap.data.local.dao.PersonDao
import com.memorymap.data.local.dao.PlaceDao
import com.memorymap.data.local.dao.UserDao
import com.memorymap.data.remote.MediaStorage
import com.memorymap.domain.model.CloudRemoval
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.LifeStats
import com.memorymap.domain.model.MonthCount
import com.memorymap.domain.model.OnThisDayItem
import com.memorymap.domain.model.User
import com.memorymap.domain.model.WipeSummary
import com.memorymap.domain.repository.OnThisDayRepository
import com.memorymap.domain.repository.UserRepository
import com.memorymap.domain.usecase.DiaryTime
import com.memorymap.util.MediaStore
import com.memorymap.util.MmLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class UserRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userDao: UserDao,
    private val memoryDao: MemoryDao,
    private val entryDao: DailyEntryDao,
    private val noteDao: DiaryNoteDao,
    private val personDao: PersonDao,
    private val placeDao: PlaceDao,
    private val mediaDao: MediaDao,
    private val syncMetaDao: SyncMetaDao,
    private val storage: MediaStorage,
) : UserRepository {

    override fun watchCurrentUser(): Flow<User?> = userDao.watchFirst().map { it?.toDomain() }

    override suspend fun getById(id: String): User? = userDao.getById(id)?.toDomain()

    override suspend fun save(user: User) = userDao.upsert(user.toEntity())

    /**
     * Removes everything belonging to one account from this device.
     *
     * Order matters. The media files go last, after the rows that point at them
     * have been counted and removed, so a failure part way through leaves
     * records without attachments rather than files nothing can reach.
     */
    override suspend fun deleteLocalData(userId: String): WipeSummary = withContext(Dispatchers.IO) {
        val summary = WipeSummary(
            memories = memoryDao.count(userId),
            entries = entryDao.count(userId),
            people = personDao.count(userId),
            places = placeDao.count(userId),
        )

        // The link tables (memory_person, memory_place, the daily_entry pairs and
        // memory_shares) cascade from their parent, so they need no query here.
        // Media has no foreign key, so it is removed explicitly.
        mediaDao.deleteForUser(userId)
        memoryDao.hardDeleteAll(userId)
        entryDao.hardDeleteAll(userId)
        noteDao.deleteAll(userId)
        personDao.deleteAll(userId)
        placeDao.deleteAll(userId)
        syncMetaDao.delete(userId)
        userDao.deleteAll()

        summary.copy(mediaFiles = MediaStore.clear(context))
    }

    override suspend fun uploadedAttachmentPaths(userId: String): List<String> =
        mediaDao.uploadedPaths(userId)

    /**
     * Deletes the uploaded files of one account, and says how many are left.
     *
     * Nothing here throws. A wipe cannot be blocked by a network, and the count
     * that comes back is the honest answer either way: an unreachable bucket
     * leaves every object unremoved, and the caller can tell the user while the
     * keys are still known.
     */
    override suspend fun deleteCloudCopies(userId: String): CloudRemoval {
        val keys = runCatching { mediaDao.uploadedPaths(userId) }.getOrElse { error ->
            MmLog.e("Could not read the uploaded attachments", error)
            return CloudRemoval()
        }
        if (keys.isEmpty()) return CloudRemoval()

        val removed = runCatching { storage.removeAll(keys) }.getOrElse { error ->
            MmLog.e("Could not remove the uploaded attachments", error)
            0
        }
        return CloudRemoval(
            removed = removed.coerceIn(0, keys.size),
            remaining = keys.size - removed.coerceIn(0, keys.size),
        )
    }
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
        // The month-day pattern also matches the current year; "on this day" is
        // about earlier years, so the day's own records are dropped here.
        return (memories + notes)
            .filter { it.year != day.year }
            .sortedByDescending { it.year }
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
