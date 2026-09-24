package com.memorymap.util

import android.content.Context
import com.memorymap.domain.model.MediaType
import java.io.File

/**
 * Where user media lives.
 *
 * Files are written to app-private external storage, so the archive survives a
 * cache clear, is not indexed by the gallery, and needs no storage permission.
 * Video stays local by default: free cloud storage is not an archive for large
 * video files, so uploading a video is always an explicit, optional action.
 */
object MediaStore {

    private const val ROOT = "memorymap"

    fun dir(context: Context, type: MediaType): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(File(base, ROOT), type.name.lowercase()).apply { mkdirs() }
    }

    /** A new, non-colliding file for an attachment of [type]. */
    fun newFile(context: Context, type: MediaType, ownerId: String, extension: String): File {
        val safeExtension = extension.trimStart('.').ifBlank { "bin" }
        return File(dir(context, type), "${ownerId}_${System.currentTimeMillis()}.$safeExtension")
    }

    /** Deletes a media file and reports whether anything was removed. */
    fun delete(file: File): Boolean = runCatching { file.takeIf { it.exists() }?.delete() ?: false }.getOrDefault(false)

    /**
     * Removes every media file on this device and reports how many went.
     *
     * Used by the account wipe. The rows are deleted separately; this only deals
     * with the bytes, which is the part a database cannot reach.
     */
    fun clear(context: Context): Int {
        val base = File(context.getExternalFilesDir(null) ?: context.filesDir, ROOT)
        if (!base.exists()) return 0
        val files = base.walkTopDown().filter { it.isFile }.toList()
        // Deepest first, so the directories are empty by the time they are removed.
        val removed = files.sortedByDescending { it.path.length }.count { it.delete() }
        base.deleteRecursively()
        return removed
    }

    /** Total bytes used by the local archive, for the profile and backup screens. */
    fun usedBytes(context: Context): Long {
        val base = File(context.getExternalFilesDir(null) ?: context.filesDir, ROOT)
        if (!base.exists()) return 0L
        return base.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
