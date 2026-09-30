package com.memorymap.domain.repository

/** What became of an attempt to write a readable copy of the archive. */
sealed interface ReadableExportOutcome {
    /** The document reached the file the user picked. */
    data object Written : ReadableExportOutcome

    /** The file could not be written - the picker was cancelled or the write failed. */
    data object Failed : ReadableExportOutcome
}

/**
 * Exports the archive as a document a person can read.
 *
 * This is not the JSON backup, which is a machine format for restoring a phone.
 * This is the memories and diary themselves, rendered as Markdown so they can be
 * printed, emailed, or opened years from now in any text editor. It is built and
 * written entirely on the device; nothing is uploaded anywhere.
 */
interface ReadableExportRepository {

    /**
     * The whole archive for [userId] as one Markdown document, newest first.
     *
     * Exposed on its own, apart from [export], because it is the half worth
     * testing: reading both record kinds, resolving their labels, and - the part
     * that matters - escaping the user's own text so it survives the trip into
     * Markdown unchanged. Writing the result to a picked file is the thin half.
     */
    suspend fun render(userId: String): String

    /** Renders the archive and writes it to the document at [documentUri]. */
    suspend fun export(userId: String, documentUri: String): ReadableExportOutcome
}
