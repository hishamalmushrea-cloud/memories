package com.memorymap.di

import android.content.Context
import androidx.room.Room
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.dao.DiaryNoteDao
import com.memorymap.data.local.dao.MediaDao
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.data.local.dao.PersonDao
import com.memorymap.data.local.dao.PlaceDao
import com.memorymap.data.local.dao.SyncMetaDao
import com.memorymap.data.local.dao.UserDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MemoryMapDatabase =
        Room.databaseBuilder(context, MemoryMapDatabase::class.java, MemoryMapDatabase.NAME)
            // No destructive migration: a diary must never be dropped silently.
            // Every schema change ships with an explicit Migration.
            .addMigrations(MemoryMapDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    @Provides fun provideUserDao(db: MemoryMapDatabase): UserDao = db.userDao()
    @Provides fun provideMemoryDao(db: MemoryMapDatabase): MemoryDao = db.memoryDao()
    @Provides fun provideDailyEntryDao(db: MemoryMapDatabase): DailyEntryDao = db.dailyEntryDao()
    @Provides fun provideDiaryNoteDao(db: MemoryMapDatabase): DiaryNoteDao = db.diaryNoteDao()
    @Provides fun provideMediaDao(db: MemoryMapDatabase): MediaDao = db.mediaDao()
    @Provides fun providePersonDao(db: MemoryMapDatabase): PersonDao = db.personDao()
    @Provides fun providePlaceDao(db: MemoryMapDatabase): PlaceDao = db.placeDao()
    @Provides fun provideSyncMetaDao(db: MemoryMapDatabase): SyncMetaDao = db.syncMetaDao()
}
