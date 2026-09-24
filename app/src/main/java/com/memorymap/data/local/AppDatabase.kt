package com.memorymap.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.TypeConverters
import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.dao.DiaryNoteDao
import com.memorymap.data.local.dao.MediaDao
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.data.local.dao.PersonDao
import com.memorymap.data.local.dao.PlaceDao
import com.memorymap.data.local.dao.SyncMetaDao
import com.memorymap.data.local.dao.UserDao
import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.DailyEntryPersonCrossRef
import com.memorymap.data.local.entities.DailyEntryPlaceCrossRef
import com.memorymap.data.local.entities.DiaryNoteEntity
import com.memorymap.data.local.entities.MediaEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.MemoryPersonCrossRef
import com.memorymap.data.local.entities.MemoryPlaceCrossRef
import com.memorymap.data.local.entities.MemoryShareEntity
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.data.local.entities.SyncMetaEntity
import com.memorymap.data.local.entities.UserEntity

/**
 * The single source of truth. Everything the user has written lives here first;
 * the server is only ever a mirror of this database.
 */
@Database(
    entities = [
        UserEntity::class,
        MemoryEntity::class,
        DailyEntryEntity::class,
        DiaryNoteEntity::class,
        MediaEntity::class,
        PersonEntity::class,
        PlaceEntity::class,
        DailyEntryPersonCrossRef::class,
        DailyEntryPlaceCrossRef::class,
        MemoryPersonCrossRef::class,
        MemoryPlaceCrossRef::class,
        MemoryShareEntity::class,
        SyncMetaEntity::class,
    ],
    version = MemoryMapDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class MemoryMapDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun memoryDao(): MemoryDao
    abstract fun dailyEntryDao(): DailyEntryDao
    abstract fun diaryNoteDao(): DiaryNoteDao
    abstract fun mediaDao(): MediaDao
    abstract fun personDao(): PersonDao
    abstract fun placeDao(): PlaceDao
    abstract fun syncMetaDao(): SyncMetaDao

    companion object {
        const val VERSION = 3
        const val NAME = "memorymap.db"

        /**
         * Adds the synchronisation bookmark table.
         *
         * Purely additive: no existing table is touched, so an installed archive
         * survives the upgrade untouched.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_meta` (" +
                        "`user_id` TEXT NOT NULL, " +
                        "`last_download_at` TEXT, " +
                        "`last_run_at` TEXT, " +
                        "`last_run_outcome` TEXT, " +
                        "PRIMARY KEY(`user_id`))",
                )
            }
        }

        /**
         * Gives `people` and `places` the columns synchronisation needs.
         *
         * Both tables were local-only until now, so every existing row is marked
         * `PENDING_CREATE`: it has never been sent, and the next run is where it
         * goes. `updated_at` is backfilled from `created_at` rather than left
         * empty, because the conflict resolver compares that column as a
         * timestamp and an empty string would lose every comparison it takes
         * part in.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                for (table in listOf("people", "places")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `updated_at` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `deleted_at` TEXT")
                    db.execSQL(
                        "ALTER TABLE `$table` ADD COLUMN `sync_status` TEXT NOT NULL " +
                            "DEFAULT 'PENDING_CREATE'",
                    )
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `last_synced_at` TEXT")
                    db.execSQL("UPDATE `$table` SET `updated_at` = `created_at` WHERE `updated_at` = ''")
                }
            }
        }
    }
}
