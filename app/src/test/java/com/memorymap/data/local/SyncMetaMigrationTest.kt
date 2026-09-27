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
 * The version 1 to 2 migration, which adds the synchronisation bookmark table.
 *
 * Run against real SQLite rather than mocked: the failure this guards against is
 * a typo in the DDL, which only an actual database will notice. What it does not
 * prove is that the result matches Room's exported schema — that is checked when
 * Room opens the database.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncMetaMigrationTest {

    @Test
    fun `the migration creates the bookmark table with its four columns`() {
        val db = openInMemoryAtVersion(1)

        MemoryMapDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT user_id, last_download_at, last_run_at, last_run_outcome FROM sync_meta")
            .use { cursor ->
                assertEquals(4, cursor.columnCount)
                assertEquals(0, cursor.count)
            }
        db.close()
    }

    @Test
    fun `the table holds one bookmark per account`() {
        val db = openInMemoryAtVersion(1)
        MemoryMapDatabase.MIGRATION_1_2.migrate(db)

        db.execSQL(
            "INSERT INTO sync_meta (user_id, last_download_at, last_run_at, last_run_outcome) " +
                "VALUES ('user-1', '2026-09-24T08:00:00Z', '2026-09-24T12:00:00', 'ok')",
        )
        db.execSQL(
            "INSERT INTO sync_meta (user_id, last_download_at) VALUES ('user-2', NULL)",
        )

        db.query("SELECT last_download_at FROM sync_meta WHERE user_id = 'user-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("2026-09-24T08:00:00Z", cursor.getString(0))
        }
        db.query("SELECT last_download_at, last_run_outcome FROM sync_meta WHERE user_id = 'user-2'")
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
        db.query("SELECT COUNT(*) FROM sync_meta").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun `running the migration twice is harmless`() {
        val db = openInMemoryAtVersion(1)

        MemoryMapDatabase.MIGRATION_1_2.migrate(db)
        MemoryMapDatabase.MIGRATION_1_2.migrate(db)

        db.query("SELECT COUNT(*) FROM sync_meta").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    @Test
    fun `the migration does not invent a watermark for an existing account`() {
        val db = openInMemoryAtVersion(1)
        MemoryMapDatabase.MIGRATION_1_2.migrate(db)

        // An account that has never synced must fetch everything on its first
        // run, which is only true while the watermark stays null.
        db.query("SELECT COUNT(*) FROM sync_meta").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        db.close()
    }

    private fun openInMemoryAtVersion(version: Int): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            // A null name means in-memory, so nothing is left behind.
            .name(null)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                },
            )
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }
}
