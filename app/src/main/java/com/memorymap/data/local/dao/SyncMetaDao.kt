package com.memorymap.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.memorymap.data.local.entities.SyncMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncMetaDao {

    @Query("SELECT * FROM sync_meta WHERE user_id = :userId LIMIT 1")
    suspend fun get(userId: String): SyncMetaEntity?

    /** The profile screen shows the last run, so this is observed, not read once. */
    @Query("SELECT * FROM sync_meta WHERE user_id = :userId LIMIT 1")
    fun watch(userId: String): Flow<SyncMetaEntity?>

    @Upsert
    suspend fun upsert(meta: SyncMetaEntity)

    @Query("DELETE FROM sync_meta WHERE user_id = :userId")
    suspend fun delete(userId: String)
}
