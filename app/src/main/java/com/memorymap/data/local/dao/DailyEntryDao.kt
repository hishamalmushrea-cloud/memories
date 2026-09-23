package com.memorymap.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.DailyEntryPersonCrossRef
import com.memorymap.data.local.entities.DailyEntryPlaceCrossRef
import kotlinx.coroutines.flow.Flow

/**
 * One row of the per-day content aggregation. Every source table contributes
 * rows of the same shape so a single grouped pass can build the week, month,
 * year and calendar views.
 */
data class DayCountRow(
    val date: String,
    val entries: Int,
    val photos: Int,
    val audio: Int,
    val videos: Int,
    val memories: Int,
    val hasDiaryNote: Int,
)

@Dao
interface DailyEntryDao {

    @Upsert
    suspend fun upsert(entry: DailyEntryEntity)

    @Upsert
    suspend fun upsertAll(entries: List<DailyEntryEntity>)

    @Query("SELECT * FROM daily_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DailyEntryEntity?

    @Query(
        """
        SELECT * FROM daily_entries
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY time DESC, created_at DESC
        """,
    )
    fun watchAll(userId: String): Flow<List<DailyEntryEntity>>

    /** The day page: every event of one diary day, in chronological order. */
    @Query(
        """
        SELECT * FROM daily_entries
        WHERE user_id = :userId AND date = :date AND deleted_at IS NULL
        ORDER BY time ASC
        """,
    )
    fun watchByDate(userId: String, date: String): Flow<List<DailyEntryEntity>>

    @Query(
        """
        SELECT * FROM daily_entries
        WHERE user_id = :userId AND deleted_at IS NULL AND date >= :from AND date <= :to
        ORDER BY date ASC, time ASC
        """,
    )
    fun watchBetween(userId: String, from: String, to: String): Flow<List<DailyEntryEntity>>

    @Query(
        """
        SELECT * FROM daily_entries
        WHERE user_id = :userId AND deleted_at IS NULL AND latitude IS NOT NULL AND longitude IS NOT NULL
        ORDER BY time DESC
        """,
    )
    fun watchLocated(userId: String): Flow<List<DailyEntryEntity>>

    /**
     * Builds every per-day counter used by week/month/year/calendar in one pass.
     * All five sources are aggregated separately and merged with UNION ALL, then
     * the repository folds the rows into `DayContentCounts`.
     */
    @Query(
        """
        SELECT date,
               SUM(entries) AS entries,
               SUM(photos) AS photos,
               SUM(audio) AS audio,
               SUM(videos) AS videos,
               SUM(memories) AS memories,
               SUM(has_diary_note) AS hasDiaryNote
        FROM (
            SELECT date, COUNT(*) AS entries, 0 AS photos, 0 AS audio, 0 AS videos, 0 AS memories, 0 AS has_diary_note
            FROM daily_entries
            WHERE user_id = :userId AND deleted_at IS NULL AND date >= :from AND date <= :to
            GROUP BY date

            UNION ALL

            SELECT e.date, 0, COUNT(*), 0, 0, 0, 0
            FROM media m
            INNER JOIN daily_entries e ON e.id = m.owner_id
            WHERE m.owner_type = 'DAILY_ENTRY' AND m.media_type = 'PHOTO' AND m.deleted_at IS NULL
              AND e.user_id = :userId AND e.deleted_at IS NULL AND e.date >= :from AND e.date <= :to
            GROUP BY e.date

            UNION ALL

            SELECT e.date, 0, 0, COUNT(*), 0, 0, 0
            FROM media m
            INNER JOIN daily_entries e ON e.id = m.owner_id
            WHERE m.owner_type = 'DAILY_ENTRY' AND m.media_type = 'AUDIO' AND m.deleted_at IS NULL
              AND e.user_id = :userId AND e.deleted_at IS NULL AND e.date >= :from AND e.date <= :to
            GROUP BY e.date

            UNION ALL

            SELECT e.date, 0, 0, 0, COUNT(*), 0, 0
            FROM media m
            INNER JOIN daily_entries e ON e.id = m.owner_id
            WHERE m.owner_type = 'DAILY_ENTRY' AND m.media_type = 'VIDEO' AND m.deleted_at IS NULL
              AND e.user_id = :userId AND e.deleted_at IS NULL AND e.date >= :from AND e.date <= :to
            GROUP BY e.date

            UNION ALL

            SELECT memory_date, 0, 0, 0, 0, COUNT(*), 0
            FROM memories
            WHERE user_id = :userId AND deleted_at IS NULL AND memory_date >= :from AND memory_date <= :to
            GROUP BY memory_date

            UNION ALL

            SELECT date, 0, 0, 0, 0, 0, 1
            FROM diary_notes
            WHERE user_id = :userId AND text <> '' AND date >= :from AND date <= :to
        )
        GROUP BY date
        ORDER BY date ASC
        """,
    )
    fun watchDayCounts(userId: String, from: String, to: String): Flow<List<DayCountRow>>

    @Query(
        """
        SELECT * FROM daily_entries
        WHERE user_id = :userId AND deleted_at IS NULL
          AND (title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%')
        ORDER BY date DESC, time DESC
        """,
    )
    suspend fun search(userId: String, query: String): List<DailyEntryEntity>

    @Query("SELECT COUNT(*) FROM daily_entries WHERE user_id = :userId AND deleted_at IS NULL")
    suspend fun count(userId: String): Int

    @Query("SELECT COUNT(DISTINCT date) FROM daily_entries WHERE user_id = :userId AND deleted_at IS NULL")
    suspend fun countRecordedDays(userId: String): Int

    // --- People / places ---

    @Query("DELETE FROM daily_entry_person WHERE entry_id = :entryId")
    suspend fun clearPeople(entryId: String)

    @Query("DELETE FROM daily_entry_place WHERE entry_id = :entryId")
    suspend fun clearPlaces(entryId: String)

    @Upsert
    suspend fun linkPerson(link: DailyEntryPersonCrossRef)

    @Upsert
    suspend fun linkPlace(link: DailyEntryPlaceCrossRef)

    @Query("SELECT person_id FROM daily_entry_person WHERE entry_id = :entryId")
    suspend fun peopleOf(entryId: String): List<String>

    @Query("SELECT place_id FROM daily_entry_place WHERE entry_id = :entryId")
    suspend fun placesOf(entryId: String): List<String>

    @Query(
        """
        SELECT e.* FROM daily_entries e
        INNER JOIN daily_entry_person ep ON ep.entry_id = e.id
        WHERE ep.person_id = :personId AND e.deleted_at IS NULL
        ORDER BY e.date DESC, e.time DESC
        """,
    )
    suspend fun entriesWithPerson(personId: String): List<DailyEntryEntity>

    @Query(
        """
        SELECT e.* FROM daily_entries e
        INNER JOIN daily_entry_place epl ON epl.entry_id = e.id
        WHERE epl.place_id = :placeId AND e.deleted_at IS NULL
        ORDER BY e.date DESC, e.time DESC
        """,
    )
    suspend fun entriesAtPlace(placeId: String): List<DailyEntryEntity>

    // --- Lifecycle ---

    @Query(
        """
        UPDATE daily_entries
        SET deleted_at = :timestamp, updated_at = :timestamp, sync_status = 'PENDING_DELETE'
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: String, timestamp: String)

    @Query("UPDATE daily_entries SET sync_status = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Query("SELECT * FROM daily_entries WHERE sync_status != 'SYNCED'")
    suspend fun pendingSync(): List<DailyEntryEntity>

    @Query("DELETE FROM daily_entries WHERE user_id = :userId")
    suspend fun hardDeleteAll(userId: String)
}

@Dao
interface DiaryNoteDao {

    @Upsert
    suspend fun upsert(note: com.memorymap.data.local.entities.DiaryNoteEntity)

    @Query("SELECT * FROM diary_notes WHERE user_id = :userId AND date = :date LIMIT 1")
    suspend fun get(userId: String, date: String): com.memorymap.data.local.entities.DiaryNoteEntity?

    @Query("SELECT * FROM diary_notes WHERE user_id = :userId AND date = :date LIMIT 1")
    fun watch(userId: String, date: String): Flow<com.memorymap.data.local.entities.DiaryNoteEntity?>

    @Query("SELECT * FROM diary_notes WHERE user_id = :userId AND date >= :from AND date <= :to ORDER BY date DESC")
    suspend fun between(userId: String, from: String, to: String): List<com.memorymap.data.local.entities.DiaryNoteEntity>

    @Query("SELECT * FROM diary_notes WHERE user_id = :userId AND date LIKE :pattern ORDER BY date DESC")
    suspend fun onThisDay(userId: String, pattern: String): List<com.memorymap.data.local.entities.DiaryNoteEntity>

    @Query("DELETE FROM diary_notes WHERE user_id = :userId")
    suspend fun deleteAll(userId: String)
}
