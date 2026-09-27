package com.memorymap.util.backup

import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlannerTest {

    @Test
    fun `archive layout matches the documented folder shape`() {
        assertEquals(
            listOf(
                "manifest.json",
                "memories.json",
                "daily_entries.json",
                "people.json",
                "places.json",
                "media.json",
            ),
            BackupLayout.JSON_FILES,
        )
        assertEquals("media", BackupLayout.MEDIA_DIR)
        assertEquals("backup", BackupLayout.ROOT_DIR)
    }

    @Test
    fun `media files are named so an import can restore them`() {
        val owner = UUID.randomUUID().toString()
        val item = media(owner, MediaType.PHOTO, "content://media/1234.jpg")
        val paths = BackupPlanner.mediaArchivePaths(listOf(item))

        assertEquals("media/${owner}_${item.id}.jpg", paths[item.id])
    }

    @Test
    fun `a uri without an extension falls back to bin`() {
        val item = media("owner", MediaType.VIDEO, "content://media/no-extension")
        val paths = BackupPlanner.mediaArchivePaths(listOf(item))
        assertTrue(paths.getValue(item.id).endsWith(".bin"))
    }

    @Test
    fun `counts split media by type`() {
        val items = listOf(
            media("a", MediaType.PHOTO, "f.jpg"),
            media("a", MediaType.PHOTO, "g.jpg"),
            media("b", MediaType.AUDIO, "h.m4a"),
            media("c", MediaType.VIDEO, "i.mp4"),
        )
        val counts = BackupPlanner.counts(memories = 5, dailyEntries = 9, people = 2, places = 3, media = items)

        assertEquals(5, counts.memories)
        assertEquals(9, counts.dailyEntries)
        assertEquals(2, counts.people)
        assertEquals(3, counts.places)
        assertEquals(2, counts.photos)
        assertEquals(1, counts.audio)
        assertEquals(1, counts.videos)
    }

    @Test
    fun `a newer archive format is rejected before importing`() {
        val manifest = BackupManifest(
            formatVersion = BACKUP_FORMAT_VERSION + 1,
            appVersion = "0.1.0",
            createdAtEpochMs = 1_700_000_000_000L,
            counts = BackupCounts(),
        )
        val problems = BackupPlanner.validate(manifest)
        assertEquals(1, problems.size)
        assertTrue(problems.first().contains("newer"))
    }

    @Test
    fun `a current archive with a valid timestamp passes`() {
        val manifest = BackupManifest(
            formatVersion = BACKUP_FORMAT_VERSION,
            appVersion = "0.1.0",
            createdAtEpochMs = 1_700_000_000_000L,
            counts = BackupCounts(),
        )
        assertTrue(BackupPlanner.validate(manifest).isEmpty())
    }

    @Test
    fun `a manifest without a timestamp is rejected`() {
        val manifest = BackupManifest(
            formatVersion = BACKUP_FORMAT_VERSION,
            appVersion = "0.1.0",
            createdAtEpochMs = 0L,
            counts = BackupCounts(),
        )
        assertTrue(BackupPlanner.validate(manifest).any { it.contains("timestamp") })
    }

    private fun media(ownerId: String, type: MediaType, uri: String) = MediaItem(
        ownerType = MediaOwner.DAILY_ENTRY,
        ownerId = ownerId,
        type = type,
        uri = uri,
    )
}
