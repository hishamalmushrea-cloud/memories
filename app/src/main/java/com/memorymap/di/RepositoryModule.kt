package com.memorymap.di

import com.memorymap.data.remote.SupabaseConfig
import com.memorymap.data.repository.DiaryRepositoryImpl
import com.memorymap.data.repository.MemoryRepositoryImpl
import com.memorymap.data.repository.OnThisDayRepositoryImpl
import com.memorymap.data.repository.ReferenceRepositoryImpl
import com.memorymap.data.repository.UserRepositoryImpl
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.domain.repository.MemoryRepository
import com.memorymap.domain.repository.OnThisDayRepository
import com.memorymap.domain.repository.ReferenceRepository
import com.memorymap.domain.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * Binds the Room-backed implementations to the domain interfaces, so the UI and
 * the use cases only ever depend on `domain.repository.*`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindMemoryRepository(impl: MemoryRepositoryImpl): MemoryRepository

    @Binds
    @Singleton
    abstract fun bindDiaryRepository(impl: DiaryRepositoryImpl): DiaryRepository

    @Binds
    @Singleton
    abstract fun bindReferenceRepository(impl: ReferenceRepositoryImpl): ReferenceRepository

    @Binds
    @Singleton
    abstract fun bindOnThisDayRepository(impl: OnThisDayRepositoryImpl): OnThisDayRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    companion object {

        @Provides
        @Singleton
        fun provideSupabaseConfig(): SupabaseConfig = SupabaseConfig.fromBuildConfig()

        /** Shared JSON configuration for backups and for Supabase payloads. */
        @Provides
        @Singleton
        fun provideJson(): Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
