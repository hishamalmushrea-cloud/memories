package com.memorymap.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A person the user can link to memories, events and days. */
@Entity(
    tableName = "people",
    indices = [Index(value = ["user_id", "name"], unique = true)],
)
data class PersonEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String = "",

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "PENDING_CREATE",

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: String? = null,
)

/** A named, reusable location pinned to the map. */
@Entity(
    tableName = "places",
    indices = [Index("user_id")],
)
data class PlaceEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "latitude")
    val latitude: Double,

    @ColumnInfo(name = "longitude")
    val longitude: Double,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String = "",

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String = "PENDING_CREATE",

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: String? = null,
)

/**
 * A media file attached to a memory or a diary entry. The URI points at the
 * app-private copy; cloud upload is always optional.
 */
@Entity(
    tableName = "media",
    indices = [
        Index(value = ["owner_type", "owner_id"]),
        Index("sync_status"),
    ],
)
data class MediaEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** `MEMORY` or `DAILY_ENTRY`. */
    @ColumnInfo(name = "owner_type")
    val ownerType: String,

    @ColumnInfo(name = "owner_id")
    val ownerId: String,

    /** `PHOTO`, `AUDIO` or `VIDEO`. */
    @ColumnInfo(name = "media_type")
    val mediaType: String,

    @ColumnInfo(name = "uri")
    val uri: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String? = null,

    @ColumnInfo(name = "width")
    val width: Int? = null,

    @ColumnInfo(name = "height")
    val height: Int? = null,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: String? = null,

    /**
     * Tombstone. A locally deleted attachment keeps its row so the next sync can
     * replay the delete; without it a media row deleted offline would come back
     * the moment the device reconnects.
     */
    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,
)
