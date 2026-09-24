package com.memorymap.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.memorymap.data.local.entities.MediaEntity
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import kotlinx.coroutines.flow.Flow

/** One row of the media-type counters used by the profile screen. */
data class MediaTypeCountRow(val mediaType: String, val count: Int)

/**
 * Per-owner attachment counters plus one photo to use as a cover.
 *
 * `MIN(uri)` is a stable pick rather than a random one: file names embed the
 * creation timestamp, so the smallest name is the earliest photo.
 */
data class MediaSummaryRow(
    val ownerId: String,
    val photos: Int,
    val audio: Int,
    val videos: Int,
    val coverUri: String?,
)

@Dao
interface PersonDao {

    @Upsert
    suspend fun upsert(person: PersonEntity)

    @Query("SELECT * FROM people WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PersonEntity?

    @Query("SELECT * FROM people WHERE user_id = :userId ORDER BY name COLLATE NOCASE ASC")
    fun watchAll(userId: String): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people WHERE user_id = :userId AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(userId: String, name: String): PersonEntity?

    @Query("SELECT * FROM people WHERE user_id = :userId AND name LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE ASC")
    suspend fun search(userId: String, query: String): List<PersonEntity>

    @Query("SELECT COUNT(*) FROM people WHERE user_id = :userId")
    suspend fun count(userId: String): Int

    /** Every row for one account, for backup export. */
    @Query("SELECT * FROM people WHERE user_id = :userId ORDER BY name COLLATE NOCASE ASC")
    suspend fun allForUser(userId: String): List<PersonEntity>

    @Query("DELETE FROM people WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM people WHERE user_id = :userId")
    suspend fun deleteAll(userId: String)
}

@Dao
interface PlaceDao {

    @Upsert
    suspend fun upsert(place: PlaceEntity)

    @Query("SELECT * FROM places WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): PlaceEntity?

    @Query("SELECT * FROM places WHERE user_id = :userId ORDER BY name COLLATE NOCASE ASC")
    fun watchAll(userId: String): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE user_id = :userId AND name LIKE '%' || :query || '%' ORDER BY name COLLATE NOCASE ASC")
    suspend fun search(userId: String, query: String): List<PlaceEntity>

    @Query("SELECT COUNT(*) FROM places WHERE user_id = :userId")
    suspend fun count(userId: String): Int

    /** Every row for one account, for backup export. */
    @Query("SELECT * FROM places WHERE user_id = :userId ORDER BY name COLLATE NOCASE ASC")
    suspend fun allForUser(userId: String): List<PlaceEntity>

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM places WHERE user_id = :userId")
    suspend fun deleteAll(userId: String)
}

@Dao
interface MediaDao {

    @Upsert
    suspend fun upsert(media: MediaEntity)

    @Upsert
    suspend fun upsertAll(media: List<MediaEntity>)

    @Query("SELECT * FROM media WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MediaEntity?

    @Query("SELECT * FROM media WHERE owner_type = :ownerType AND owner_id = :ownerId AND deleted_at IS NULL ORDER BY created_at ASC")
    fun watchFor(ownerType: String, ownerId: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE owner_type = :ownerType AND owner_id = :ownerId AND deleted_at IS NULL ORDER BY created_at ASC")
    suspend fun getFor(ownerType: String, ownerId: String): List<MediaEntity>

    /** Every live attachment belonging to any of the given owners, in one pass. */
    @Query("SELECT * FROM media WHERE owner_id IN (:ownerIds) AND deleted_at IS NULL ORDER BY created_at ASC")
    suspend fun activeForOwners(ownerIds: List<String>): List<MediaEntity>

    @Query("SELECT media_type AS mediaType, COUNT(*) AS count FROM media WHERE deleted_at IS NULL GROUP BY media_type")
    suspend fun countByType(): List<MediaTypeCountRow>

    /** Attachment counters per owner, so a list can show media without N queries. */
    @Query(
        """
        SELECT owner_id AS ownerId,
               SUM(CASE WHEN media_type = 'PHOTO' THEN 1 ELSE 0 END) AS photos,
               SUM(CASE WHEN media_type = 'AUDIO' THEN 1 ELSE 0 END) AS audio,
               SUM(CASE WHEN media_type = 'VIDEO' THEN 1 ELSE 0 END) AS videos,
               MIN(CASE WHEN media_type = 'PHOTO' THEN uri END) AS coverUri
        FROM media
        WHERE owner_type = :ownerType AND deleted_at IS NULL
        GROUP BY owner_id
        """,
    )
    fun watchSummary(ownerType: String): Flow<List<MediaSummaryRow>>

    @Query("SELECT * FROM media WHERE sync_status != 'SYNCED'")
    suspend fun pendingSync(): List<MediaEntity>

    /**
     * Soft delete: keeps the tombstone so the server delete can be replayed after
     * a reconnect. The file itself is removed by the repository.
     */
    @Query(
        """
        UPDATE media
        SET deleted_at = :timestamp, sync_status = 'PENDING_DELETE'
        WHERE id = :id
        """,
    )
    suspend fun softDelete(id: String, timestamp: String)

    /** Soft-deletes every live attachment of one memory or diary entry. */
    @Query(
        """
        UPDATE media
        SET deleted_at = :timestamp, sync_status = 'PENDING_DELETE'
        WHERE owner_type = :ownerType AND owner_id = :ownerId AND deleted_at IS NULL
        """,
    )
    suspend fun softDeleteFor(ownerType: String, ownerId: String, timestamp: String)

    /** Tombstones awaiting a replayed server delete, for the sync worker. */
    @Query("SELECT * FROM media WHERE deleted_at IS NOT NULL")
    suspend fun tombstones(): List<MediaEntity>

    @Query("UPDATE media SET sync_status = :status, last_synced_at = :timestamp WHERE id = :id")
    suspend fun markSynced(id: String, status: String, timestamp: String)

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM media WHERE owner_type = :ownerType AND owner_id = :ownerId")
    suspend fun deleteFor(ownerType: String, ownerId: String)

    /** Rows the sync worker may purge once the server has confirmed the delete. */
    @Query("DELETE FROM media WHERE id = :id AND deleted_at IS NOT NULL")
    suspend fun purgeTombstone(id: String)
}
