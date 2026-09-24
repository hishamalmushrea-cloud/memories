package com.memorymap.util

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Converts timestamps at the boundary between Room and the server.
 *
 * Room stores naive local text (`2026-09-24T21:03:11.482`) because that is what
 * the rest of the app writes and what reads back correctly on the calendar. The
 * server column is `timestamptz`, which needs an instant. Sending the naive text
 * would make Postgres read it as UTC and shift every record by the device's
 * offset, so the conversion happens here, in one tested place.
 *
 * A value that cannot be parsed is passed through untouched: losing a timestamp
 * is recoverable, aborting a sync is not.
 */
object SyncTime {

    /** Naive local Room text to an instant the server can store. */
    fun toInstantText(localText: String?): String? {
        if (localText.isNullOrBlank()) return null
        return try {
            LocalDateTime.parse(localText)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toString()
        } catch (_: DateTimeParseException) {
            localText
        }
    }

    /** A server instant to the naive local text Room stores. */
    fun toLocalText(instantText: String?): String? {
        if (instantText.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(instantText)
                .atZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime()
                .toString()
        } catch (_: DateTimeParseException) {
            instantText
        }
    }
}
