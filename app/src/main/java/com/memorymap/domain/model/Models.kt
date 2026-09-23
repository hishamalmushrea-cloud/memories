package com.memorymap.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

/** A geo coordinate pair. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** An account. The id is the Supabase user id when the user is signed in. */
data class User(
    val id: String = UUID.randomUUID().toString(),
    val email: String,
    val displayName: String,
    val avatarUrl: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

/** An important, standalone event that can be pinned to the map. */
data class Memory(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val title: String,
    val text: String = "",
    val location: GeoPoint? = null,
    val placeName: String? = null,
    val memoryDate: LocalDate,
    val emotion: Emotion = Emotion.NOSTALGIA,
    val visibility: Visibility = Visibility.PRIVATE,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val deletedAt: LocalDateTime? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING_CREATE,
    val lastSyncedAt: LocalDateTime? = null,
) {
    val isDeleted: Boolean get() = deletedAt != null
}

/**
 * One event inside one day. The day ([date]) is the diary unit and the event is
 * the unit inside the day, which is what makes the timeline work.
 */
data class DailyEntry(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val date: LocalDate,
    val time: LocalDateTime,
    val title: String,
    val text: String = "",
    val location: GeoPoint? = null,
    val placeId: String? = null,
    val emotion: Emotion? = null,
    val linkedMemoryId: String? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val deletedAt: LocalDateTime? = null,
    val syncStatus: SyncStatus = SyncStatus.PENDING_CREATE,
    val lastSyncedAt: LocalDateTime? = null,
) {
    val isDeleted: Boolean get() = deletedAt != null
}

/** Media file attached to a memory or a diary entry. */
data class MediaItem(
    val id: String = UUID.randomUUID().toString(),
    val ownerType: MediaOwner,
    val ownerId: String,
    val type: MediaType,
    val uri: String,
    val mimeType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val syncStatus: SyncStatus = SyncStatus.PENDING_CREATE,
    val lastSyncedAt: LocalDateTime? = null,
)

/** Which table a [MediaItem] belongs to. */
enum class MediaOwner { MEMORY, DAILY_ENTRY }

/** A person the user can link to memories, events and days. */
data class Person(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

/** A named, reusable location. */
data class Place(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val name: String,
    val location: GeoPoint,
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

/** A row of the "on this day" card: same month and day, another year. */
data class OnThisDayItem(
    val year: Int,
    val title: String,
    val isMemory: Boolean,
)

/**
 * Aggregated counters for one calendar day, used by the week, month, year and
 * calendar screens. Everything here is counted from data the user typed in;
 * nothing is generated.
 */
data class DayContentCounts(
    val date: LocalDate,
    val entries: Int,
    val photos: Int,
    val audio: Int,
    val videos: Int,
    val memories: Int,
    val hasDiaryNote: Boolean,
) {
    val hasContent: Boolean
        get() = entries > 0 || photos > 0 || audio > 0 || videos > 0 || memories > 0 || hasDiaryNote
}

/** Plain counters for the profile and life-statistics screens. */
data class LifeStats(
    val recordedDays: Int,
    val memories: Int,
    val events: Int,
    val places: Int,
    val photos: Int,
    val audio: Int,
    val videos: Int,
    val topEmotion: Emotion?,
    val topMonth: MonthCount?,
)

/** How often one calendar month was used, e.g. September 2026 -> 12. */
data class MonthCount(val year: Int, val month: Int, val count: Int)

/** Converts an epoch-millis timestamp to the device zone. */
fun Instant.toLocal(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    LocalDateTime.ofInstant(this, zone)
