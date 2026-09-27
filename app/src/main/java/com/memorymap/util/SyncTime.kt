package com.memorymap.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * The one place that knows how a moment is written down.
 *
 * A memory edited on a plane is compared with the copy on the server, and the
 * comparison has to survive the phone having been in Sanaa when it was written
 * and in London when it was read. That only works if what is stored is an
 * **instant** - a moment with its offset attached - rather than a wall-clock
 * reading that means something different in each zone.
 *
 * What this class stores, and what it does:
 *
 *   * `nowText()` is what every writer stamps with: `2026-09-26T22:31:05.123Z`.
 *   * `asInstantText()` reads either shape and answers in the instant shape.
 *     Text that already carries a `Z` or an offset is normalised; text without
 *     one is read in the device's own zone, which is the only honest reading of
 *     a row written before this existed. Anything unparseable is passed through
 *     untouched: losing a timestamp is recoverable, aborting a sync is not.
 *   * `instant()` and `local()` are for reading one back - the first for
 *     comparing, the second for showing, both in the device's own zone.
 *
 * The old names are kept for the callers that still read a *rendering* rather
 * than a stamp: `toLocalText` is what a screen wants, and `toInstantText` is now
 * `asInstantText` under its former name.
 *
 * The failure this replaced is worth stating, because it was silent: the
 * watermark and the conflict resolver used to compare a stamp written in one
 * zone against one written in another and pick the older edit as the winner.
 * The user saw a memory they had just edited revert to yesterday's version, once,
 * with no error anywhere.
 */
object SyncTime {

    /** The stamp every writer uses: an instant, with its offset, in UTC. */
    fun nowText(): String = Instant.now().toString()

    /**
     * Anything - an instant, an offset, or a legacy wall-clock reading - as an
     * instant. Null stays null, and text that cannot be read is returned as it
     * came, so a bad value travels on instead of being dropped.
     */
    fun asInstantText(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return instant(text)?.toString() ?: text
    }

    /** Reads a stamp back as a moment, for comparisons. */
    fun instant(text: String?): Instant? {
        if (text.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(text).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                // A row written before stamps carried an offset. Read in this
                // device's zone, which is what the code that wrote it assumed.
                LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: DateTimeParseException) {
                try {
                    // `Instant.parse` is stricter than `OffsetDateTime.parse`, and
                    // is what a value straight from `Instant.toString()` needs.
                    Instant.parse(text)
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }

    /** Reads a stamp back as the local time to show, in this device's zone. */
    fun local(text: String?): LocalDateTime? = instant(text)?.atZone(ZoneId.systemDefault())?.toLocalDateTime()

    /** A wall-clock reading from this device as the instant it is. */
    fun localAsText(value: LocalDateTime): String =
        value.atZone(ZoneId.systemDefault()).toInstant().toString()

    // --- the two conversions the sync path used before instants -------------

    /** Legacy name for [asInstantText], kept so existing callers read the same. */
    fun toInstantText(localText: String?): String? = asInstantText(localText)

    /**
     * The local rendering of a stamp, for the places that show one - not for
     * storing one. Kept because a screen renders a timestamp in the device's own
     * zone and that is exactly what this returns.
     */
    fun toLocalText(instantText: String?): String? {
        if (instantText.isNullOrBlank()) return null
        return local(instantText)?.toString() ?: instantText
    }
}
