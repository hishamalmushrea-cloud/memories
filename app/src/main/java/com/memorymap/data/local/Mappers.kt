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
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The only place that knows how entities and domain models look. The UI never
 * sees a Room type and Room never sees a domain type.
 */
object Mappers {

    fun LocalDate.iso(): String = toString()
    fun LocalDateTime.iso(): String = toString()

    private fun String?.toDate(): LocalDate? = this?.takeIf { it.isNotBlank() }?.let(LocalDate::parse)
    private fun String?.toDateTime(): LocalDateTime? = this?.takeIf { it.isNotBlank() }?.let(LocalDateTime::parse)

    // --- User ---

    fun UserEntity.toDomain(): User = User(
        id = id,
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        createdAt = createdAt.toDateTime() ?: LocalDateTime.now(),
    )

    fun User.toEntity(): UserEntity = UserEntity(
        id = id,
        email = email,
        displayName = displayName,
        avatarUrl = avatarUrl,
        createdAt = createdAt.iso(),
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
        createdAt = createdAt.toDateTime() ?: LocalDateTime.now(),
        updatedAt = updatedAt.toDateTime() ?: LocalDateTime.now(),
        deletedAt = deletedAt.toDateTime(),
        syncStatus = SyncStatus.fromName(syncStatus),
        lastSyncedAt = lastSyncedAt.toDateTime(),
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
        createdAt = createdAt.iso(),
        updatedAt = updatedAt.iso(),
        deletedAt = deletedAt?.iso(),
        syncStatus = syncStatus.name,
        lastSyncedAt = lastSyncedAt?.iso(),
    )

    // --- Daily entry ---

    fun DailyEntryEntity.toDomain(): DailyEntry = DailyEntry(
        id = id,
        userId = userId,
        date = date.toDate() ?: LocalDate.now(),
        time = time.toDateTime() ?: LocalDateTime.now(),
        title = title,
        text = text,
        location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude!!) else null,
        placeId = placeId,
        emotion = Emotion.fromName(emotion),
        linkedMemoryId = linkedMemoryId,
        createdAt = createdAt.toDateTime() ?: LocalDateTime.now(),
        updatedAt = updatedAt.toDateTime() ?: LocalDateTime.now(),
        deletedAt = deletedAt.toDateTime(),
        syncStatus = SyncStatus.fromName(syncStatus),
        lastSyncedAt = lastSyncedAt.toDateTime(),
    )

    fun DailyEntry.toEntity(): DailyEntryEntity = DailyEntryEntity(
        id = id,
        userId = userId,
        date = date.iso(),
        time = time.iso(),
        title = title,
        text = text,
        latitude = location?.latitude,
        longitude = location?.longitude,
        placeId = placeId,
        emotion = emotion?.name,
        linkedMemoryId = linkedMemoryId,
        createdAt = createdAt.iso(),
        updatedAt = updatedAt.iso(),
        deletedAt = deletedAt?.iso(),
        syncStatus = syncStatus.name,
        lastSyncedAt = lastSyncedAt?.iso(),
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
        createdAt = createdAt.toDateTime() ?: LocalDateTime.now(),
        updatedAt = updatedAt.toDateTime() ?: (createdAt.toDateTime() ?: LocalDateTime.now()),
        syncStatus = SyncStatus.fromName(syncStatus),
        lastSyncedAt = lastSyncedAt.toDateTime(),
        deletedAt = deletedAt.toDateTime(),
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
        createdAt = createdAt.iso(),
        updatedAt = updatedAt.iso(),
        syncStatus = syncStatus.name,
        lastSyncedAt = lastSyncedAt?.iso(),
        deletedAt = deletedAt?.iso(),
        storagePath = storagePath,
        uploadRequested = uploadRequested,
    )

    // --- People / places ---

    fun PersonEntity.toDomain(): Person = Person(
        id = id,
        userId = userId,
        name = name,
        createdAt = createdAt.toDateTime() ?: LocalDateTime.now(),
    )

    fun Person.toEntity(): PersonEntity = PersonEntity(
        id = id,
        userId = userId,
        name = name,
        createdAt = createdAt.iso(),
    )

    fun PlaceEntity.toDomain(): Place = Place(
        id = id,
        userId = userId,
        name = name,
        location = GeoPoint(latitude, longitude),
        createdAt = createdAt.toDateTime() ?: LocalDateTime.now(),
    )

    fun Place.toEntity(): PlaceEntity = PlaceEntity(
        id = id,
        userId = userId,
        name = name,
        latitude = location.latitude,
        longitude = location.longitude,
        createdAt = createdAt.iso(),
    )

    // --- Diary note ---

    fun DiaryNoteEntity.toText(): String = text

    fun diaryNote(userId: String, date: LocalDate, text: String): DiaryNoteEntity = DiaryNoteEntity(
        userId = userId,
        date = date.iso(),
        text = text,
        updatedAt = LocalDateTime.now().iso(),
        syncStatus = SyncStatus.PENDING_UPDATE.name,
    )
}
