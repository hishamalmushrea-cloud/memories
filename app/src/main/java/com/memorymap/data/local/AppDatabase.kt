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
        const val VERSION = 2
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
    }
}
