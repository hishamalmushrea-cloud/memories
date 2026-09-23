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

    @Query("SELECT * FROM media WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY created_at ASC")
    fun watchFor(ownerType: String, ownerId: String): Flow<List<MediaEntity>>

    @Query("SELECT * FROM media WHERE owner_type = :ownerType AND owner_id = :ownerId ORDER BY created_at ASC")
    suspend fun getFor(ownerType: String, ownerId: String): List<MediaEntity>

    @Query("SELECT media_type AS mediaType, COUNT(*) AS count FROM media GROUP BY media_type")
    suspend fun countByType(): List<MediaTypeCountRow>

    @Query("SELECT * FROM media WHERE sync_status != 'SYNCED'")
    suspend fun pendingSync(): List<MediaEntity>

    @Query("UPDATE media SET sync_status = :status, last_synced_at = :timestamp WHERE id = :id")
    suspend fun markSynced(id: String, status: String, timestamp: String)

    @Query("DELETE FROM media WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM media WHERE owner_type = :ownerType AND owner_id = :ownerId")
    suspend fun deleteFor(ownerType: String, ownerId: String)
}
