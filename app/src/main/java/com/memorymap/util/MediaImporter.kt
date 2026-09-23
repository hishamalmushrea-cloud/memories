package com.memorymap.util

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaType
import java.io.File

/**
 * Copies picked or captured media into app-private storage and registers it.
 *
 * Only three kinds of file are accepted: image, audio and video. The importer
 * never transcribes audio and never analyses video; it reads just enough
 * metadata to show a thumbnail, a duration and a file size.
 *
 * The pure helpers ([mediaTypeFor], [extensionFor]) are free of Android types so
 * they can be unit tested without a device.
 */
object MediaImporter {

    /** Maps a MIME type onto the three supported attachment kinds. */
    fun mediaTypeFor(mimeType: String?): MediaType? {
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        if (mime.isEmpty()) return null
        return when {
            mime.startsWith("image/") -> MediaType.PHOTO
            mime.startsWith("audio/") -> MediaType.AUDIO
            mime.startsWith("video/") -> MediaType.VIDEO
            else -> null
        }
    }

    /** A file extension for [mimeType], used when the source name has none. */
    fun extensionFor(mimeType: String?): String {
        val mime = mimeType?.trim()?.lowercase().orEmpty()
        return when (mime) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/heic" -> "heic"
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/aac" -> "aac"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/ogg" -> "ogg"
            "audio/amr" -> "amr"
            "audio/wav", "audio/x-wav" -> "wav"
            "video/mp4" -> "mp4"
            "video/3gpp" -> "3gp"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            else -> "bin"
        }
    }

    /**
     * Copies [source] into the app-private archive and returns the attachment to
     * store, or null when the type is unsupported or the copy failed.
     */
    fun import(
        context: Context,
        source: Uri,
        owner: MediaOwner,
        ownerId: String,
    ): MediaItem? {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(source)
        val type = mediaTypeFor(mimeType) ?: return null

        val target = MediaStore.newFile(context, type, ownerId, extensionFor(mimeType))
        val copied = runCatching {
            resolver.openInputStream(source)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: false
        }.getOrDefault(false)

        // An empty copy is worse than no attachment at all.
        if (!copied || !target.exists() || target.length() == 0L) {
            MediaStore.delete(target)
            return null
        }

        val dimensions = if (type == MediaType.PHOTO) imageBounds(target) else null
        val duration = if (type == MediaType.PHOTO) null else durationMs(target)

        return MediaItem(
            ownerId = ownerId,
            ownerType = owner,
            type = type,
            uri = target.absolutePath,
            mimeType = mimeType,
            width = dimensions?.first,
            height = dimensions?.second,
            durationMs = duration,
        )
    }

    /**
     * Imports a file the app captured itself (camera photo or voice recording),
     * which is already inside the archive and therefore needs no copy.
     */
    fun adopt(
        file: File,
        type: MediaType,
        owner: MediaOwner,
        ownerId: String,
        mimeType: String? = null,
    ): MediaItem? {
        if (!file.exists() || file.length() == 0L) return null
        val dimensions = if (type == MediaType.PHOTO) imageBounds(file) else null
        val duration = if (type == MediaType.PHOTO) null else durationMs(file)
        return MediaItem(
            ownerId = ownerId,
            ownerType = owner,
            type = type,
            uri = file.absolutePath,
            mimeType = mimeType,
            width = dimensions?.first,
            height = dimensions?.second,
            durationMs = duration,
        )
    }

    /** Reads only the bounds, so large photos never land in memory. */
    private fun imageBounds(file: File): Pair<Int, Int>? = runCatching {
        BitmapFactory.Options().apply { inJustDecodeBounds = true }.let { options ->
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth > 0 && options.outHeight > 0) {
                options.outWidth to options.outHeight
            } else {
                null
            }
        }
    }.getOrNull()

    /** Duration in milliseconds for an audio or video file. */
    private fun durationMs(file: File): Long? = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.takeIf { it > 0L }
        }
    }.getOrNull()

    /** Formats a duration as `m:ss`, used by the audio and video rows. */
    fun formatDuration(durationMs: Long?): String {
        if (durationMs == null || durationMs <= 0L) return "0:00"
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
