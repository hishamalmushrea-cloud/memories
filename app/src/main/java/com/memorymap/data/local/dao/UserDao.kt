package com.memorymap.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.memorymap.data.local.entities.UserEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserDao {

    @Upsert
    suspend fun upsert(user: UserEntity)

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): UserEntity?

    @Query("SELECT * FROM users WHERE id = :id LIMIT 1")
    fun watchById(id: String): Flow<UserEntity?>

    @Query("SELECT * FROM users LIMIT 1")
    fun watchFirst(): Flow<UserEntity?>

    @Query("DELETE FROM users")
    suspend fun deleteAll()
}
