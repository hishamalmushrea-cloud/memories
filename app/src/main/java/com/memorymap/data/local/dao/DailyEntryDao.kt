package com.memorymap.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.memorymap.data.sync.LocalRow
import com.memorymap.data.sync.PendingRow
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

/**
 * A note read back for synchronisation, addressed by the handle its table uses.
 *
 * `diary_notes` has no id column: it is keyed by `(user_id, note_date)`. Every
 * sync table hands the engine a single opaque id per row, so a note's id is
 * `user_id || '|' || date`, composed in SQL by the queries below. The row has no
 * tombstone on either side, which is why there is no `deleted` field here.
 */
data class NoteSyncRow(val id: String, val updatedAt: String)

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

    /**
     * Replaces the whole set of people linked to one event.
     *
     * Not additive on purpose: a link removed in the editor has to be gone
     * afterwards, and the same whole-set replace is what lets a download from
     * another device take an unlink with it.
     */
    @Transaction
    suspend fun replacePeople(entryId: String, personIds: List<String>) {
        clearPeople(entryId)
        personIds.forEach { linkPerson(DailyEntryPersonCrossRef(entryId, it)) }
    }

    @Transaction
    suspend fun replacePlaces(entryId: String, placeIds: List<String>) {
        clearPlaces(entryId)
        placeIds.forEach { linkPlace(DailyEntryPlaceCrossRef(entryId, it)) }
    }

    @Query(
        "SELECT entry_id AS ownerId, person_id AS refId " +
            "FROM daily_entry_person WHERE entry_id IN (:entryIds)",
    )
    suspend fun personLinks(entryIds: List<String>): List<LinkRow>

    @Query(
        "SELECT entry_id AS ownerId, place_id AS refId " +
            "FROM daily_entry_place WHERE entry_id IN (:entryIds)",
    )
    suspend fun placeLinks(entryIds: List<String>): List<LinkRow>

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


    /**
     * Every live row for one account.
     *
     * Used by search when the user filtered by date or emotion without typing a
     * word, so there is no text query to narrow with.
     */
    @Query("SELECT * FROM daily_entries WHERE user_id = :userId AND deleted_at IS NULL")
    suspend fun allForUser(userId: String): List<DailyEntryEntity>

    // --- Synchronisation (Phase 6) ---------------------------------------
    // Everything that is not SYNCED still needs work: the three pending states
    // and SYNC_ERROR, which is how a failed row is retried on the next run.

    @Query(
        "SELECT id AS id, updated_at AS updatedAt, (deleted_at IS NOT NULL) AS deleted " +
            "FROM daily_entries WHERE user_id = :userId AND sync_status != 'SYNCED' " +
            "ORDER BY updated_at ASC",
    )
    suspend fun pendingForSync(userId: String): List<PendingRow>

    @Query(
        "SELECT id AS id, updated_at AS updatedAt, (deleted_at IS NOT NULL) AS deleted " +
            "FROM daily_entries WHERE id IN (:ids)",
    )
    suspend fun syncSnapshot(ids: List<String>): List<LocalRow>

    @Query("SELECT COUNT(*) FROM daily_entries WHERE user_id = :userId AND sync_status != 'SYNCED'")
    fun watchPendingSyncCount(userId: String): Flow<Int>

    @Query("UPDATE daily_entries SET sync_status = 'SYNCED', last_synced_at = :at WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, at: String)

    @Query("UPDATE daily_entries SET sync_status = 'SYNC_ERROR' WHERE id IN (:ids)")
    suspend fun markSyncError(ids: List<String>)
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

    // --- Synchronisation (Phase 6) ---------------------------------------
    // The handle is `user_id || '|' || date` in every query below and in
    // DiaryNoteSyncTable.noteHandle, which builds the same string for a record
    // arriving from the server. The two are pinned together by a test.

    @Query(
        "SELECT user_id || '|' || date AS id, updated_at AS updatedAt FROM diary_notes " +
            "WHERE user_id = :userId AND sync_status != 'SYNCED' ORDER BY updated_at ASC",
    )
    suspend fun pendingForSync(userId: String): List<NoteSyncRow>

    @Query(
        "SELECT user_id || '|' || date AS id, updated_at AS updatedAt FROM diary_notes " +
            "WHERE user_id || '|' || date IN (:ids)",
    )
    suspend fun syncSnapshot(ids: List<String>): List<NoteSyncRow>

    @Query("SELECT COUNT(*) FROM diary_notes WHERE user_id = :userId AND sync_status != 'SYNCED'")
    fun watchPendingSyncCount(userId: String): Flow<Int>

    @Query("SELECT * FROM diary_notes WHERE user_id || '|' || date IN (:ids)")
    suspend fun byHandles(ids: List<String>): List<com.memorymap.data.local.entities.DiaryNoteEntity>

    @Upsert
    suspend fun upsertAll(notes: List<com.memorymap.data.local.entities.DiaryNoteEntity>)

    @Query(
        "UPDATE diary_notes SET sync_status = 'SYNCED', last_synced_at = :at " +
            "WHERE user_id || '|' || date IN (:ids)",
    )
    suspend fun markSynced(ids: List<String>, at: String)

    @Query("UPDATE diary_notes SET sync_status = 'SYNC_ERROR' WHERE user_id || '|' || date IN (:ids)")
    suspend fun markSyncError(ids: List<String>)

    @Query("DELETE FROM diary_notes WHERE user_id = :userId")
    suspend fun deleteAll(userId: String)
}
