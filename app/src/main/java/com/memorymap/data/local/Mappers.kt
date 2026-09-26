package com.memorymap.data.local

import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.DiaryNoteEntity
import com.memorymap.data.local.entities.MediaEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.data.local.entities.UserEntity
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.Person
import com.memorymap.domain.model.Place
import com.memorymap.domain.model.SyncStatus
import com.memorymap.domain.model.User
import com.memorymap.domain.model.Visibility
import com.memorymap.util.SyncTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The only place that knows how entities and domain models look. The UI never
 * sees a Room type and Room never sees a domain type.
 */
object Mappers {

    fun LocalDate.iso(): String = toString()

    /**
     * A moment as Room stores it: an instant, offset and all.
     *
     * `LocalDateTime.now()` says what the clock on this phone read; it does not
     * say which zone that clock was in. Storing the reading is what let a memory
     * edited in one country be judged older than one edited the day before in
     * another, so every stamp that is compared or sent goes through here and
     * comes out as a moment.
     */
    fun LocalDateTime.stamp(): String = atZone(ZoneId.systemDefault()).toInstant().toString()

    /**
     * A stamp as the app reads it back - into this device's own zone, so the
     * calendar and the labels show local time. Rows written before stamps became
     * instants hold plain local text, and both read the same way.
     */
    private fun String?.toStamp(): LocalDateTime? = SyncTime.local(this)

    private fun String?.toDate(): LocalDate? = this?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)

    // --- User ---

    fun UserEntity.toDomain(): User = User(
        id = id,
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        createdAt = createdAt.toStamp() ?: LocalDateTime.now(),
    )

    fun User.toEntity(): UserEntity = UserEntity(
        id = id,
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        createdAt = createdAt.stamp(),
    )

    // --- Memory ---

    fun MemoryEntity.toDomain(): Memory = Memory(
        id = id,
        userId = userId,
        title = title,
        text = text,
        location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude!!) else null,
        placeName = placeName,
        memoryDate = memoryDate.toDate() ?: LocalDate.now(),
        emotion = Emotion.fromName(emotion) ?: Emotion.NOSTALGIA,
        visibility = Visibility.fromName(visibility),
        createdAt = createdAt.toStamp() ?: LocalDateTime.now(),
        updatedAt = updatedAt.toStamp() ?: LocalDateTime.now(),
        deletedAt = deletedAt.toStamp(),
        syncStatus = SyncStatus.fromName(syncStatus),
        lastSyncedAt = lastSyncedAt.toStamp(),
    )

    fun Memory.toEntity(): MemoryEntity = MemoryEntity(
        id = id,
        userId = userId,
        title = title,
        text = text,
        latitude = location?.latitude,
        longitude = location?.longitude,
        placeName = placeName,
        memoryDate = memoryDate.iso(),
        emotion = emotion.name,
        visibility = visibility.name,
        createdAt = createdAt.stamp(),
        updatedAt = updatedAt.stamp(),
        deletedAt = deletedAt?.stamp(),
        syncStatus = syncStatus.name,
        lastSyncedAt = lastSyncedAt?.stamp(),
    )

    // --- Daily entry ---

    fun DailyEntryEntity.toDomain(): DailyEntry = DailyEntry(
        id = id,
        userId = userId,
        date = date.toDate() ?: LocalDate.now(),
        time = time.toStamp() ?: LocalDateTime.now(),
        title = title,
        text = text,
        location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude!!) else null,
        placeId = placeId,
        emotion = Emotion.fromName(emotion),
        linkedMemoryId = linkedMemoryId,
        createdAt = createdAt.toStamp() ?: LocalDateTime.now(),
        updatedAt = updatedAt.toStamp() ?: LocalDateTime.now(),
        deletedAt = deletedAt.toStamp(),
        syncStatus = SyncStatus.fromName(syncStatus),
        lastSyncedAt = lastSyncedAt.toStamp(),
    )

    fun DailyEntry.toEntity(): DailyEntryEntity = DailyEntryEntity(
        id = id,
        userId = userId,
        date = date.iso(),
        time = time.stamp(),
        title = title,
        text = text,
        latitude = location?.latitude,
        longitude = location?.longitude,
        placeId = placeId,
        emotion = emotion?.name,
        linkedMemoryId = linkedMemoryId,
        createdAt = createdAt.stamp(),
        updatedAt = updatedAt.stamp(),
        deletedAt = deletedAt?.stamp(),
        syncStatus = syncStatus.name,
        lastSyncedAt = lastSyncedAt?.stamp(),
    )

    // --- Media ---

    fun MediaEntity.toDomain(): MediaItem = MediaItem(
        id = id,
        ownerType = if (ownerType == MediaOwner.MEMORY.name) MediaOwner.MEMORY else MediaOwner.DAILY_ENTRY,
        ownerId = ownerId,
        type = MediaType.fromName(mediaType) ?: MediaType.PHOTO,
        uri = uri,
        mimeType = mimeType,
        width = width,
        height = height,
        durationMs = durationMs,
        createdAt = createdAt.toStamp() ?: LocalDateTime.now(),
        updatedAt = updatedAt.toStamp() ?: (createdAt.toStamp() ?: LocalDateTime.now()),
        syncStatus = SyncStatus.fromName(syncStatus),
        lastSyncedAt = lastSyncedAt.toStamp(),
        deletedAt = deletedAt.toStamp(),
        storagePath = storagePath,
        uploadRequested = uploadRequested,
    )

    fun MediaItem.toEntity(): MediaEntity = MediaEntity(
        id = id,
        ownerType = ownerType.name,
        ownerId = ownerId,
        mediaType = type.name,
        uri = uri,
        mimeType = mimeType,
        width = width,
        height = height,
        durationMs = durationMs,
        createdAt = createdAt.stamp(),
        updatedAt = updatedAt.stamp(),
        syncStatus = syncStatus.name,
        lastSyncedAt = lastSyncedAt?.stamp(),
        deletedAt = deletedAt?.stamp(),
        storagePath = storagePath,
        uploadRequested = uploadRequested,
    )

    // --- People / places ---

    fun PersonEntity.toDomain(): Person = Person(
        id = id,
        userId = userId,
        name = name,
        createdAt = createdAt.toStamp() ?: LocalDateTime.now(),
    )

    fun Person.toEntity(): PersonEntity = PersonEntity(
        id = id,
        userId = userId,
        name = name,
        createdAt = createdAt.stamp(),
    )

    fun PlaceEntity.toDomain(): Place = Place(
        id = id,
        userId = userId,
        name = name,
        location = GeoPoint(latitude, longitude),
        createdAt = createdAt.toStamp() ?: LocalDateTime.now(),
    )

    fun Place.toEntity(): PlaceEntity = PlaceEntity(
        id = id,
        userId = userId,
        name = name,
        latitude = location.latitude,
        longitude = location.longitude,
        createdAt = createdAt.stamp(),
    )

    // --- Diary note ---

    fun DiaryNoteEntity.toText(): String = text

    fun diaryNote(userId: String, date: LocalDate, text: String): DiaryNoteEntity = DiaryNoteEntity(
        userId = userId,
        date = date.iso(),
        text = text,
        updatedAt = SyncTime.nowText(),
        syncStatus = SyncStatus.PENDING_UPDATE.name,
    )
}
