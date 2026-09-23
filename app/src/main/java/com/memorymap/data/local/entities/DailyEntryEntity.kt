package com.memorymap.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One event inside one day. [date] is the diary day and [time] is when the event
 * happened inside it, which is what makes the day read as an ordered log.
 */
@Entity(
    tableName = "daily_entries",
    indices = [
        Index("user_id"),
        Index("date"),
        Index("deleted_at"),
        Index("sync_status"),
        Index("place_id"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PlaceEntity::class,
            parentColumns = ["id"],
            childColumns = ["place_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class DailyEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "user_id")
    val userId: String,

    /** ISO-8601 `yyyy-MM-dd`, the diary day this event belongs to. */
    @ColumnInfo(name = "date")
    val date: String,

    /** ISO-8601 local date-time of the event itself. */
    @ColumnInfo(name = "time")
    val time: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "latitude")
    val latitude: Double? = null,

    @ColumnInfo(name = "longitude")
    val longitude: Double? = null,

    @ColumnInfo(name = "place_id")
    val placeId: String? = null,

    @ColumnInfo(name = "emotion")
    val emotion: String? = null,

    /** Optional link turning this event into a pointer at a [MemoryEntity]. */
    @ColumnInfo(name = "linked_memory_id")
    val linkedMemoryId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "deleted_at")
    val deletedAt: String? = null,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: String? = null,
)

/**
 * The free-form end-of-day note ("what did I do today?"). One note per user per
 * day; splitting it into timed events stays a manual action by the user.
 */
@Entity(
    tableName = "diary_notes",
    primaryKeys = ["user_id", "date"],
    indices = [Index("date")],
)
data class DiaryNoteEntity(
    @ColumnInfo(name = "user_id")
    val userId: String,

    /** ISO-8601 `yyyy-MM-dd`. */
    @ColumnInfo(name = "date")
    val date: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "updated_at")
    val updatedAt: String,

    @ColumnInfo(name = "sync_status")
    val syncStatus: String,

    @ColumnInfo(name = "last_synced_at")
    val lastSyncedAt: String? = null,
)

/** Diary event <-> person link. */
@Entity(
    tableName = "daily_entry_person",
    primaryKeys = ["entry_id", "person_id"],
    foreignKeys = [
        ForeignKey(
            entity = DailyEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
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
data class DailyEntryPersonCrossRef(
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "person_id") val personId: String,
)

/** Diary event <-> place link, for events attached to a named place. */
@Entity(
    tableName = "daily_entry_place",
    primaryKeys = ["entry_id", "place_id"],
    foreignKeys = [
        ForeignKey(
            entity = DailyEntryEntity::class,
            parentColumns = ["id"],
            childColumns = ["entry_id"],
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
data class DailyEntryPlaceCrossRef(
    @ColumnInfo(name = "entry_id") val entryId: String,
    @ColumnInfo(name = "place_id") val placeId: String,
)
