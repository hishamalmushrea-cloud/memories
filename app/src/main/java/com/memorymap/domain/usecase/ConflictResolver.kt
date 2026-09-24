package com.memorymap.domain.usecase

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Decides which copy of a record wins when the phone and the server disagree.
 *
 * This is last-write-wins on `updated_at`, with one rule that overrides it:
 * **a local delete always wins over a copy that is still alive on the server.**
 * Without that rule a record deleted on a plane comes back the moment the phone
 * finds a network again, which is the failure the spec calls out by name.
 *
 * The cost of that rule is stated plainly: if the same record was deleted here
 * and edited elsewhere while offline, the edit is lost. For a single person's
 * own archive, honouring their explicit delete is the safer mistake to make.
 *
 * Pure, so every branch is tested on the JVM.
 */
object ConflictResolver {

    enum class Winner { LOCAL, REMOTE }

    fun resolve(
        localUpdatedAt: String?,
        localDeleted: Boolean,
        remoteUpdatedAt: String?,
        remoteDeleted: Boolean,
    ): Winner {
        val local = parse(localUpdatedAt)
        val remote = parse(remoteUpdatedAt)

        return when {
            // A timestamp we cannot read is not a reason to overwrite anything
            // the user can still see, so the local copy stands.
            local == null || remote == null -> Winner.LOCAL

            // Deleted here, alive there: the delete is honoured, whatever the
            // clocks say. This is the rule that stops deleted data returning.
            localDeleted && !remoteDeleted -> Winner.LOCAL

            // Deleted there, alive here: the delete wins only if this device has
            // not edited the record since. A later local edit is kept.
            remoteDeleted && !localDeleted ->
                if (local.compareTo(remote) <= 0) Winner.REMOTE else Winner.LOCAL

            // Both alive, or both deleted: the newer edit wins, and a tie keeps
            // the local copy because that is the one the user is looking at.
            local.compareTo(remote) >= 0 -> Winner.LOCAL
            else -> Winner.REMOTE
        }
    }

    /**
     * Reads a timestamp written by either side.
     *
     * The server sends instants with an offset; Room holds naive local text,
     * which is what the rest of the app writes. Treating naive text as the
     * device's own zone keeps the two comparable.
     */
    internal fun parse(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
