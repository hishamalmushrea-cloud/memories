package com.memorymap.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.memorymap.data.sync.LocalRow
import com.memorymap.data.sync.PendingRow
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.MemoryPersonCrossRef
import com.memorymap.data.local.entities.MemoryPlaceCrossRef
import com.memorymap.data.local.entities.MemoryShareEntity
import kotlinx.coroutines.flow.Flow

/** A (ISO date -> count) pair, the shape every per-day aggregation returns. */
data class DateCount(val date: String, val count: Int)

/**
 * One row of a link table, read as a pair.
 *
 * Shared by both owners - memories and diary events - because a link is the
 * same shape either way, and one type lets the sync tables treat the four link
 * tables alike.
 */
data class LinkRow(val ownerId: String, val refId: String)

/** One row of the emotion distribution used by the life statistics screen. */
data class EmotionCountRow(val emotion: String, val count: Int)

/** One row of the per-calendar-month memory count (`month` is `yyyy-MM`). */
data class MonthCountRow(val month: String, val count: Int)

@Dao
interface MemoryDao {

    @Upsert
    suspend fun upsert(memory: MemoryEntity)

    @Upsert
    suspend fun upsertAll(memories: List<MemoryEntity>)

    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MemoryEntity?

    /** Reactive single read, so the detail screen updates after an edit. */
    @Query("SELECT * FROM memories WHERE id = :id LIMIT 1")
    fun watchById(id: String): Flow<MemoryEntity?>

    @Query(
        """
        SELECT * FROM memories
        WHERE user_id = :userId AND deleted_at IS NULL
        ORDER BY memory_date DESC, created_at DESC
        """,
    )
    fun watchAll(userId: String): Flow<List<MemoryEntity>>

    @Query(
        """
        SELECT * FROM memories
        WHERE user_id = :userId AND deleted_at IS NULL AND latitude IS NOT NULL AND longitude IS NOT NULL
        ORDER BY memory_date DESC
        """,
    )
    fun watchLocated(userId: String): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories WHERE user_id = :userId AND memory_date = :date AND deleted_at IS NULL")
    fun watchByDate(userId: String, date: String): Flow<List<MemoryEntity>>

    @Query(
        """
        SELECT * FROM memories
        WHERE user_id = :userId AND deleted_at IS NULL
          AND memory_date >= :from AND memory_date <= :to
        ORDER BY memory_date ASC
        """,
    )
    fun watchBetween(userId: String, from: String, to: String): Flow<List<MemoryEntity>>

    /**
     * "On this day": same month and day, any other year. [pattern] is built by
     * `DiaryTime.sameMonthDayPattern`, e.g. `____-09-23`.
     */
    @Query(
        """
        SELECT * FROM memories
        WHERE user_id = :userId AND deleted_at IS NULL AND memory_date LIKE :pattern
        ORDER BY memory_date DESC
        """,
    )
    suspend fun onThisDay(userId: String, pattern: String): List<MemoryEntity>

    @Query("SELECT memory_date AS date, COUNT(*) AS count FROM memories WHERE user_id = :userId AND deleted_at IS NULL GROUP BY memory_date")
    fun watchCountByDate(userId: String): Flow<List<DateCount>>

    @Query("SELECT emotion, COUNT(*) AS count FROM memories WHERE user_id = :userId AND deleted_at IS NULL GROUP BY emotion ORDER BY count DESC")
    suspend fun emotionDistribution(userId: String): List<EmotionCountRow>

    /**
     * Memory count per calendar month, busiest first. `month` is `yyyy-MM`,
     * produced by `substr(memory_date, 1, 7)` on the ISO date column.
     */
    @Query(
        """
        SELECT substr(memory_date, 1, 7) AS month, COUNT(*) AS count
        FROM memories
        WHERE user_id = :userId AND deleted_at IS NULL
        GROUP BY month
        ORDER BY count DESC
        """,
    )
    suspend fun topMonths(userId: String): List<MonthCountRow>

    @Query("SELECT COUNT(*) FROM memories WHERE user_id = :userId AND deleted_at IS NULL")
    suspend fun count(userId: String): Int

    /** Local, full-text-ish search over title and body. */
    @Query(
        """
        SELECT * FROM memories
        WHERE user_id = :userId AND deleted_at IS NULL
          AND (title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%' OR place_name LIKE '%' || :query || '%')
        ORDER BY memory_date DESC
        """,
    )
    suspend fun search(userId: String, query: String): List<MemoryEntity>

    @Query(
        """
        SELECT * FROM memories
        WHERE user_id = :userId AND deleted_at IS NULL AND memory_date >= :from AND memory_date <= :to
        ORDER BY memory_date DESC
        """,
    )
    suspend fun searchByDateRange(userId: String, from: String, to: String): List<MemoryEntity>

    // --- People / places / shares ---

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkPerson(link: MemoryPersonCrossRef)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkPlace(link: MemoryPlaceCrossRef)

    @Query("DELETE FROM memory_person WHERE memory_id = :memoryId")
    suspend fun clearPeople(memoryId: String)

    @Query("DELETE FROM memory_place WHERE memory_id = :memoryId")
    suspend fun clearPlaces(memoryId: String)

    @Query("SELECT person_id FROM memory_person WHERE memory_id = :memoryId")
    suspend fun peopleOf(memoryId: String): List<String>

    @Query("SELECT place_id FROM memory_place WHERE memory_id = :memoryId")
    suspend fun placesOf(memoryId: String): List<String>

    // --- Batch link reads, for synchronisation ---
    // One query for a whole batch: the sync engine pushes and pulls in batches,
    // and asking per record would be one round trip to the database each.

    @Query(
        "SELECT memory_id AS ownerId, person_id AS refId " +
            "FROM memory_person WHERE memory_id IN (:memoryIds)",
    )
    suspend fun personLinks(memoryIds: List<String>): List<LinkRow>

    @Query(
        "SELECT memory_id AS ownerId, place_id AS refId " +
            "FROM memory_place WHERE memory_id IN (:memoryIds)",
    )
    suspend fun placeLinks(memoryIds: List<String>): List<LinkRow>

    @Upsert
    suspend fun upsertShare(share: MemoryShareEntity)

    @Query("DELETE FROM memory_shares WHERE memory_id = :memoryId AND shared_with_user_id = :sharedWithUserId")
    suspend fun revokeShare(memoryId: String, sharedWithUserId: String)

    @Query("SELECT * FROM memory_shares WHERE memory_id = :memoryId")
    suspend fun sharesOf(memoryId: String): List<MemoryShareEntity>

    /** Every memory a given person appears in, newest first. */
    @Query(
        """
        SELECT m.* FROM memories m
        INNER JOIN memory_person mp ON mp.memory_id = m.id
        WHERE mp.person_id = :personId AND m.deleted_at IS NULL
        ORDER BY m.memory_date DESC
        """,
    )
    suspend fun memoriesWithPerson(personId: String): List<MemoryEntity>

    /** Every memory pinned to a given place. */
    @Query(
        """
        SELECT m.* FROM memories m
        INNER JOIN memory_place mpl ON mpl.memory_id = m.id
        WHERE mpl.place_id = :placeId AND m.deleted_at IS NULL
        ORDER BY m.memory_date DESC
        """,
    )
    suspend fun memoriesAtPlace(placeId: String): List<MemoryEntity>

    // --- Lifecycle ---

    /** Soft delete: keeps the tombstone so the server delete can be replayed. */
    @Query(
        """
        UPDATE memories
        SET deleted_at = :timestamp, updated_at = :timestamp, sync_status = 'PENDING_DELETE'
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: String, timestamp: String)

    @Query("UPDATE memories SET sync_status = :status WHERE id = :id")
    suspend fun updateSyncStatus(id: String, status: String)

    @Query("SELECT * FROM memories WHERE sync_status != 'SYNCED'")
    suspend fun pendingSync(): List<MemoryEntity>

    @Transaction
    suspend fun replacePeople(memoryId: String, personIds: List<String>) {
        clearPeople(memoryId)
        personIds.forEach { linkPerson(MemoryPersonCrossRef(memoryId, it)) }
    }

    @Transaction
    suspend fun replacePlaces(memoryId: String, placeIds: List<String>) {
        clearPlaces(memoryId)
        placeIds.forEach { linkPlace(MemoryPlaceCrossRef(memoryId, it)) }
    }

    @Query("DELETE FROM memories WHERE user_id = :userId")
    suspend fun hardDeleteAll(userId: String)


    /**
     * Every live row for one account.
     *
     * Used by search when the user filtered by date or emotion without typing a
     * word, so there is no text query to narrow with.
     */
    @Query("SELECT * FROM memories WHERE user_id = :userId AND deleted_at IS NULL")
    suspend fun allForUser(userId: String): List<MemoryEntity>

    // --- Synchronisation (Phase 6) ---------------------------------------
    // Everything that is not SYNCED still needs work: the three pending states
    // and SYNC_ERROR, which is how a failed row is retried on the next run.

    @Query(
        "SELECT id AS id, updated_at AS updatedAt, (deleted_at IS NOT NULL) AS deleted " +
            "FROM memories WHERE user_id = :userId AND sync_status != 'SYNCED' " +
            "ORDER BY updated_at ASC",
    )
    suspend fun pendingForSync(userId: String): List<PendingRow>

    @Query(
        "SELECT id AS id, updated_at AS updatedAt, (deleted_at IS NOT NULL) AS deleted " +
            "FROM memories WHERE id IN (:ids)",
    )
    suspend fun syncSnapshot(ids: List<String>): List<LocalRow>

    @Query("SELECT COUNT(*) FROM memories WHERE user_id = :userId AND sync_status != 'SYNCED'")
    fun watchPendingSyncCount(userId: String): Flow<Int>

    @Query("UPDATE memories SET sync_status = 'SYNCED', last_synced_at = :at WHERE id IN (:ids)")
    suspend fun markSynced(ids: List<String>, at: String)

    @Query("UPDATE memories SET sync_status = 'SYNC_ERROR' WHERE id IN (:ids)")
    suspend fun markSyncError(ids: List<String>)
}
