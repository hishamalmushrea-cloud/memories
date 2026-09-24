package com.memorymap.domain.repository

import com.memorymap.util.backup.BackupCounts
import com.memorymap.util.backup.BackupManifest

/**
 * Result of a backup operation.
 *
 * `Failed` carries a string resource key, not a sentence: the reason has to be
 * readable in the user's language, and the repository does not know which one
 * that is.
 */
sealed interface BackupOutcome {

    /** [mediaMissing] counts attachments whose file was gone from this device. */
    data class Exported(
        val manifest: BackupManifest,
        val mediaCopied: Int,
        val mediaMissing: Int,
    ) : BackupOutcome

    /**
     * [skipped] counts rows the archive held that this device already had a
     * newer copy of, or had deliberately deleted. An import is a merge, never a
     * wipe, so those rows survive.
     */
    data class Imported(
        val counts: BackupCounts,
        val mediaRestored: Int,
        val skipped: Int,
    ) : BackupOutcome

    data class Failed(val messageKey: String) : BackupOutcome
}

/**
 * Export and import of a local archive.
 *
 * The archive lives in a folder the user picked, not in app-private storage, so
 * getting their life back never depends on this app or on a server being up.
 */
interface BackupRepository {

    /** Writes every record of [userId] into the folder at [treeUri]. */
    suspend fun export(userId: String, treeUri: String): BackupOutcome

    /**
     * Reads the manifest of an existing archive without touching any data, so
     * the user can see what they are about to import first.
     */
    suspend fun inspect(treeUri: String): BackupManifest?

    /** Merges the archive at [treeUri] into the records of [userId]. */
    suspend fun import(userId: String, treeUri: String): BackupOutcome
}
