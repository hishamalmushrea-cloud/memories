package com.memorymap.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The version 2 to 3 migration, which makes `people` and `places` syncable.
 *
 * Run against real SQLite, because the failure this guards against is DDL that
 * SQLite rejects - and an `ALTER TABLE` that adds a `NOT NULL` column to a table
 * that already holds rows is exactly the kind of statement that only fails on a
 * populated database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReferenceSyncMigrationTest {

    @Test
    fun `both tables gain the four synchronisation columns`() {
        val db = openVersion2WithArchive()

        MemoryMapDatabase.MIGRATION_2_3.migrate(db)

        for (table in listOf("people", "places")) {
            db.query("SELECT updated_at, deleted_at, sync_status, last_synced_at FROM $table")
                .use { cursor -> assertEquals(table, 4, cursor.columnCount) }
        }
        db.close()
    }

    @Test
    fun `existing rows are queued for a first upload and carry their creation time`() {
        val db = openVersion2WithArchive()

        MemoryMapDatabase.MIGRATION_2_3.migrate(db)

        // These rows were written before synchronisation existed, so they have
        // never been sent. Marking them pending is what makes the next run
        // upload them instead of assuming the server already has them.
        for (table in listOf("people", "places")) {
            db.query(
                "SELECT updated_at, sync_status, deleted_at FROM $table WHERE id = 'p1'",
            ).use { cursor ->
                assertTrue(table, cursor.moveToFirst())
                // An empty updated_at would lose every conflict comparison it
                // took part in, so it is backfilled from created_at.
                assertEquals(table, "2024-01-01T00:00:00", cursor.getString(0))
                assertEquals(table, "PENDING_CREATE", cursor.getString(1))
                assertTrue(table, cursor.isNull(2))
            }
        }
        db.close()
    }

    @Test
    fun `running the migration twice is refused by sqlite, so it must run once`() {
        val db = openVersion2WithArchive()
        MemoryMapDatabase.MIGRATION_2_3.migrate(db)

        // Unlike the version 1 migration, this one is not idempotent: ALTER TABLE
        // ADD COLUMN has no IF NOT EXISTS. Room tracks the version, so it is
        // never asked twice - asserted here so the assumption is written down.
        val failed = runCatching { MemoryMapDatabase.MIGRATION_2_3.migrate(db) }.isFailure
        assertTrue("a second run should fail on a duplicate column", failed)
        db.close()
    }

    /**
     * The version 3 shape of `media`, with one row in it.
     *
     * Populated for the same reason the people and places one is: the columns
     * added here include a `NOT NULL` one, and SQLite only refuses that on a
     * table that already holds rows.
     */
    @Test
    fun `an attachment gains a stamp, a place for its bytes and no request`() {
        val db = openVersion3WithAnAttachment()

        MemoryMapDatabase.MIGRATION_3_4.migrate(db)

        db.query(
            "SELECT updated_at, storage_path, upload_requested, created_at FROM media " +
                "WHERE id = 'm1'",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            // Backfilled from created_at, because an empty stamp loses every
            // conflict comparison and would let a deletion pass unseen.
            assertEquals("2024-01-01T00:00:00", cursor.getString(0))
            assertTrue("nothing has been uploaded yet", cursor.isNull(1))
            // Opted out, which is the honest answer for a row that predates the
            // feature: uploading is never something this app decides alone.
            assertEquals(0, cursor.getInt(2))
            assertEquals("2024-01-01T00:00:00", cursor.getString(3))
        }
        db.close()
    }

    /**
     * The version 2 shape of the two tables, with one row in each.
     *
     * Populated on purpose: adding a `NOT NULL` column to an empty table proves
     * nothing about what happens to an installed archive.
     */
    /** The version 3 shape of `media`, which had no cloud columns at all. */
    private fun openVersion3WithAnAttachment(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                },
            )
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
        db.execSQL(
            "CREATE TABLE media (" +
                "id TEXT NOT NULL PRIMARY KEY, owner_type TEXT NOT NULL, owner_id TEXT NOT NULL, " +
                "media_type TEXT NOT NULL, uri TEXT NOT NULL, mime_type TEXT, width INTEGER, " +
                "height INTEGER, duration_ms INTEGER, created_at TEXT NOT NULL, " +
                "sync_status TEXT NOT NULL, last_synced_at TEXT, deleted_at TEXT)",
        )
        db.execSQL(
            "INSERT INTO media (id, owner_type, owner_id, media_type, uri, created_at, sync_status) " +
                "VALUES ('m1', 'MEMORY', 'memory-1', 'PHOTO', '/tmp/a.jpg', " +
                "'2024-01-01T00:00:00', 'SYNCED')",
        )
        return db
    }

    private fun openVersion2WithArchive(): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                },
            )
            .build()
        val db = FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
        db.execSQL(
            "CREATE TABLE people (" +
                "id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL, " +
                "name TEXT NOT NULL, created_at TEXT NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE places (" +
                "id TEXT NOT NULL PRIMARY KEY, user_id TEXT NOT NULL, name TEXT NOT NULL, " +
                "latitude REAL NOT NULL, longitude REAL NOT NULL, created_at TEXT NOT NULL)",
        )
        db.execSQL(
            "INSERT INTO people (id, user_id, name, created_at) " +
                "VALUES ('p1', 'user-1', 'أحمد', '2024-01-01T00:00:00')",
        )
        db.execSQL(
            "INSERT INTO places (id, user_id, name, latitude, longitude, created_at) " +
                "VALUES ('p1', 'user-1', 'إب', 13.97, 44.17, '2024-01-01T00:00:00')",
        )
        return db
    }
}
