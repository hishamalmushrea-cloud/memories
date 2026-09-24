package com.memorymap.util.backup

import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Layout of a local backup archive. The archive is a plain folder the user owns:
 *
 * ```text
 * backup/
 * ├── manifest.json
 * ├── memories.json
 * ├── daily_entries.json
 * ├── people.json
 * ├── places.json
 * ├── media.json
 * └── media/
 * ```
 *
 * `media.json` indexes the attachments in `media/`, so an import can put each
 * file back on the record it belonged to instead of guessing from a name.
 *
 * Keeping the format explicit means the user is never dependent on Supabase to
 * get their own life back.
 */
object BackupLayout {
    const val ROOT_DIR = "backup"
    const val MANIFEST = "manifest.json"
    const val MEMORIES = "memories.json"
    const val DAILY_ENTRIES = "daily_entries.json"
    const val PEOPLE = "people.json"
    const val PLACES = "places.json"
    const val MEDIA = "media.json"
    const val MEDIA_DIR = "media"

    /** Relative paths of every JSON document inside an archive. */
    val JSON_FILES: List<String> = listOf(MANIFEST, MEMORIES, DAILY_ENTRIES, PEOPLE, PLACES, MEDIA)
}

/** Current archive format version. Bumped whenever a field is added or renamed. */
const val BACKUP_FORMAT_VERSION = 1

@Serializable
data class BackupManifest(
    @SerialName("format_version") val formatVersion: Int = BACKUP_FORMAT_VERSION,
    @SerialName("app_version") val appVersion: String,
    @SerialName("created_at_epoch_ms") val createdAtEpochMs: Long,
    val counts: BackupCounts,
)

@Serializable
data class BackupCounts(
    val memories: Int = 0,
    @SerialName("daily_entries") val dailyEntries: Int = 0,
    val people: Int = 0,
    val places: Int = 0,
    val photos: Int = 0,
    val audio: Int = 0,
    val videos: Int = 0,
)

/**
 * Decides what goes into an archive. Pure planning only, no file access, which
 * is why it is unit-tested directly.
 */
object BackupPlanner {

    /**
     * Maps media attachments to their destination path inside `media/`.
     * Files are named `<ownerId>_<mediaId>.<ext>` so an import can restore them
     * to the exact record they belonged to.
     */
    fun mediaArchivePaths(items: List<MediaItem>): Map<String, String> = items.associate { item ->
        val ext = item.uri.substringAfterLast('.', "bin")
        item.id to "${BackupLayout.MEDIA_DIR}/${item.ownerId}_${item.id}.$ext"
    }

    /** Counts media by type so the manifest can be written without a second pass. */
    fun counts(
        memories: Int,
        dailyEntries: Int,
        people: Int,
        places: Int,
        media: List<MediaItem>,
    ): BackupCounts = BackupCounts(
        memories = memories,
        dailyEntries = dailyEntries,
        people = people,
        places = places,
        photos = media.count { it.type == MediaType.PHOTO },
        audio = media.count { it.type == MediaType.AUDIO },
        videos = media.count { it.type == MediaType.VIDEO },
    )

    /**
     * Validates a manifest read back from disk before anything is imported.
     * Returns a human-readable list of problems; empty means the archive is safe
     * to import.
     */
    fun validate(manifest: BackupManifest): List<String> {
        val problems = mutableListOf<String>()
        if (manifest.formatVersion > BACKUP_FORMAT_VERSION) {
            problems += "Archive format ${manifest.formatVersion} is newer than this app supports " +
                "(max $BACKUP_FORMAT_VERSION)."
        }
        if (manifest.formatVersion < 1) {
            problems += "Archive format version is invalid: ${manifest.formatVersion}."
        }
        if (manifest.createdAtEpochMs <= 0L) {
            problems += "Archive creation timestamp is missing."
        }
        return problems
    }
}
