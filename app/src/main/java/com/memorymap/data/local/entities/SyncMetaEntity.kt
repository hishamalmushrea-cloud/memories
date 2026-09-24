package com.memorymap.data.local.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Where synchronisation left off for one account.
 *
 * [lastDownloadAt] is the watermark: the newest server `updated_at` this device
 * has already seen. The next download asks only for rows newer than it, so a
 * phone that syncs every fifteen minutes does not re-read the whole archive each
 * time. On a first sync it is null and everything the user owns is fetched.
 *
 * It is stored in its own table rather than derived from the rows, because the
 * rows only know about themselves: an account whose newest record was edited a
 * year ago would otherwise re-download the world on every run.
 */
@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey
    @ColumnInfo(name = "user_id")
    val userId: String,

    /** Server-side instant, or null before the first successful download. */
    @ColumnInfo(name = "last_download_at")
    val lastDownloadAt: String? = null,

    /** Local naive text, used only to tell the user when sync last ran. */
    @ColumnInfo(name = "last_run_at")
    val lastRunAt: String? = null,

    /**
     * A stable key describing the last run: `ok`, `empty` or `error`.
     *
     * Deliberately not a sentence — the database must not hold text in one
     * language that the UI then has to display in another.
     */
    @ColumnInfo(name = "last_run_outcome")
    val lastRunOutcome: String? = null,
)
