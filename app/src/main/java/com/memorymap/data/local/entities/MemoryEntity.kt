package com.memorymap.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An important, standalone event. `deleted_at` is a tombstone: the row survives
 * a local delete until the server confirms it, so deleted data cannot come back.
 */
@Entity(
    tableName = "memories",
    indices = [
        Index("user_id"),
        Index("memory_date"),
        Index("deleted_at"),
        Index("sync_status"),
    ],
)
data class MemoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    @ColumnInfo(name = "place_name")
    val placeName: String? = null,

    /** ISO-8601 `yyyy-MM-dd`. */
    @ColumnInfo(name = "memory_date")
    val memoryDate: String,

    /** One of [com.memorymap.domain.model.Emotion]. */
    @ColumnInfo(name = "emotion")
    val emotion: String,

    /** One of [com.memorymap.domain.model.Visibility]. */
    @ColumnInfo(name = "visibility")
    val visibility: String,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,

    /** One of [com.memorymap.domain.model.SyncStatus]. */
    @ColumnInfo(name = "sync_status")
    val syncStatus: String,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: String? = null,
)

/** Memory <-> person link. */
@Entity(
    tableName = "memory_person",
    primaryKeys = ["memory_id", "person_id"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PersonEntity::class,
            parentColumns = ["id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("person_id")],
)
data class MemoryPersonCrossRef(
    @ColumnInfo(name = "memory_id") val memoryId: String,
    @ColumnInfo(name = "person_id") val personId: String,
)

/** Memory <-> place link. */
@Entity(
    tableName = "memory_place",
    primaryKeys = ["memory_id", "place_id"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["place_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("place_id")],
)
data class MemoryPlaceCrossRef(
    @ColumnInfo(name = "memory_id") val memoryId: String,
    @ColumnInfo(name = "place_id") val placeId: String,
)

/**
 * Explicit share grant. A record is only visible to another account when a row
 * exists here; `SHARED` visibility without a row means nobody but the owner.
 */
@Entity(
    tableName = "memory_shares",
    primaryKeys = ["memory_id", "shared_with_user_id"],
    foreignKeys = [
        ForeignKey(
            entity = MemoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["memory_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("shared_with_user_id")],
)
data class MemoryShareEntity(
    @ColumnInfo(name = "memory_id") val memoryId: String,
    @ColumnInfo(name = "shared_with_user_id") val sharedWithUserId: String,
    @ColumnInfo(name = "role") val role: String = "viewer",
    @ColumnInfo(name = "created_at") val createdAt: String,
)
