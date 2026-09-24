package com.memorymap.util.backup

import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.MediaEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.domain.model.SyncStatus
import com.memorymap.util.backup.BackupMappers.toBackup
import com.memorymap.util.backup.BackupMappers.toEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an archive document contains, and what it must not.
 *
 * A backup is the user's own copy of their life, so it carries the content of a
 * record and none of this phone's bookkeeping: no sync status, no tombstone, no
 * last-synced stamp. Restoring those onto a different device would be a claim
 * about a server that device has never talked to.
 */
class BackupRecordsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private fun memory(
        id: String = "m1",
        updatedAt: String = "2024-03-01T10:00:00",
    ) = MemoryEntity(
        id = id,
        userId = "u1",
        title = "رحلة إلى صنعاء",
        text = "كان الجو صحواً",
        latitude = 15.369445,
        longitude = 44.191007,
        placeName = "صنعاء",
        memoryDate = "2024-03-01",
        emotion = "HAPPY",
        visibility = "PRIVATE",
        createdAt = "2024-03-01T09:00:00",
        updatedAt = updatedAt,
        deletedAt = "2025-01-02T08:00:00",
        syncStatus = "PENDING_UPDATE",
        lastSyncedAt = "2024-04-01T00:00:00",
    )

    @Test
    fun `a memory survives a round trip through the archive`() {
        val original = memory()

        val restored = original.toBackup().toEntity()

        assertEquals(original.id, restored.id)
        assertEquals(original.userId, restored.userId)
        assertEquals(original.title, restored.title)
        assertEquals(original.text, restored.text)
        assertEquals(original.latitude, restored.latitude)
        assertEquals(original.longitude, restored.longitude)
        assertEquals(original.placeName, restored.placeName)
        assertEquals(original.memoryDate, restored.memoryDate)
        assertEquals(original.emotion, restored.emotion)
        assertEquals(original.visibility, restored.visibility)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.updatedAt, restored.updatedAt)
    }

    @Test
    fun `a restored record is pending so it is actually pushed`() {
        val restored = memory().toBackup().toEntity()

        assertEquals(SyncStatus.PENDING_CREATE.name, restored.syncStatus)
    }

    @Test
    fun `a record replacing an existing row is pending as an update`() {
        val restored = memory().toBackup().toEntity(SyncStatus.PENDING_UPDATE)

        assertEquals(SyncStatus.PENDING_UPDATE.name, restored.syncStatus)
    }

    @Test
    fun `device state is left out of the archive`() {
        val encoded = json.encodeToString(memory().toBackup())

        assertFalse("sync status is device state", encoded.contains("sync_status"))
        assertFalse("a tombstone is device state", encoded.contains("deleted_at"))
        assertFalse("a sync stamp is device state", encoded.contains("last_synced_at"))
    }

    @Test
    fun `field names on disk are stable snake case`() {
        val encoded = json.encodeToString(memory().toBackup())

        assertTrue(encoded.contains("\"user_id\""))
        assertTrue(encoded.contains("\"memory_date\""))
        assertTrue(encoded.contains("\"place_name\""))
        assertTrue(encoded.contains("\"created_at\""))
        assertTrue(encoded.contains("\"updated_at\""))
    }

    @Test
    fun `an archive from a newer app still reads`() {
        // A field this version does not know about must not make the whole
        // document unreadable; that is what keeps an archive readable later.
        val document = """
            [{"id":"m1","user_id":"u1","title":"t","text":"","memory_date":"2024-03-01",
              "emotion":"HAPPY","visibility":"PRIVATE","created_at":"2024-03-01T09:00:00",
              "updated_at":"2024-03-01T10:00:00","added_in_version_9":"something"}]
        """.trimIndent()

        val rows = json.decodeFromString(ListSerializer(MemoryBackup.serializer()), document)

        assertEquals(1, rows.size)
        assertEquals("m1", rows.first().id)
    }

    @Test
    fun `a daily entry keeps its optional links`() {
        val original = DailyEntryEntity(
            id = "e1",
            userId = "u1",
            date = "2024-03-01",
            time = "21:40:00",
            title = "مساء",
            text = "قرأت قليلاً",
            latitude = null,
            longitude = null,
            placeId = "place-1",
            emotion = "NOSTALGIA",
            linkedMemoryId = "m1",
            createdAt = "2024-03-01T21:40:00",
            updatedAt = "2024-03-01T21:41:00",
            syncStatus = "SYNCED",
        )

        val restored = original.toBackup().toEntity()

        assertEquals(original.placeId, restored.placeId)
        assertEquals(original.emotion, restored.emotion)
        assertEquals(original.linkedMemoryId, restored.linkedMemoryId)
        assertEquals(original.date, restored.date)
        assertEquals(original.time, restored.time)
        assertNull(restored.latitude)
    }

    @Test
    fun `people and places round trip`() {
        // The format stores no updated_at for these two, and a restore derives
        // it from created_at, so the round trip is exact for a row whose
        // updated_at already is its created_at - which is what a row that has
        // never been edited looks like.
        val person = PersonEntity(
            id = "p1",
            userId = "u1",
            name = "أحمد",
            createdAt = "2024-01-01T00:00:00",
            updatedAt = "2024-01-01T00:00:00",
        )
        val place = PlaceEntity(
            id = "pl1",
            userId = "u1",
            name = "الحديدة",
            latitude = 14.796,
            longitude = 42.954,
            createdAt = "2024-01-01T00:00:00",
            updatedAt = "2024-01-01T00:00:00",
        )

        assertEquals(person, person.toBackup().toEntity())
        assertEquals(place, place.toBackup().toEntity())
    }

    @Test
    fun `an attachment records where its bytes live in the archive`() {
        val original = MediaEntity(
            id = "media-1",
            ownerType = "MEMORY",
            ownerId = "m1",
            mediaType = "PHOTO",
            uri = "/storage/emulated/0/Android/data/com.memorymap/files/memorymap/photo/m1_1.jpg",
            mimeType = "image/jpeg",
            width = 1080,
            height = 1920,
            durationMs = null,
            createdAt = "2024-03-01T10:00:00",
            syncStatus = "SYNCED",
        )

        val archived = original.toBackup("media/m1_media-1.jpg")

        assertEquals("media/m1_media-1.jpg", archived.archivePath)
        // The path on the device that wrote the archive is meaningless anywhere
        // else, so it is not carried across.
        assertFalse(archived.archivePath.contains("storage"))
    }

    @Test
    fun `a restored attachment points at where it landed on this device`() {
        val archived = MediaBackup(
            id = "media-1",
            ownerType = "MEMORY",
            ownerId = "m1",
            mediaType = "PHOTO",
            archivePath = "media/m1_media-1.jpg",
            mimeType = "image/jpeg",
            createdAt = "2024-03-01T10:00:00",
        )

        val restored = archived.toEntity("/data/data/com.memorymap/files/memorymap/photo/m1_9.jpg")

        assertEquals("/data/data/com.memorymap/files/memorymap/photo/m1_9.jpg", restored.uri)
        assertEquals(SyncStatus.PENDING_CREATE.name, restored.syncStatus)
    }
}
