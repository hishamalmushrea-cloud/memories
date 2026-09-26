package com.memorymap.di

import com.memorymap.data.remote.SecureSessionStore
import com.memorymap.BuildConfig
import com.memorymap.data.map.MapProviders
import com.memorymap.data.remote.MediaStorage
import com.memorymap.data.remote.SupabaseMediaStorage
import com.memorymap.data.remote.PostgrestSyncApi
import com.memorymap.data.remote.SupabaseConfig
import com.memorymap.data.remote.SyncApi
import com.memorymap.data.repository.BackupRepositoryImpl
import com.memorymap.data.repository.DiaryRepositoryImpl
import com.memorymap.data.repository.SupabaseAuthRepository
import com.memorymap.data.local.LocalMediaFileStore
import com.memorymap.data.local.MediaFileStore
import com.memorymap.data.repository.MediaRepositoryImpl
import com.memorymap.data.repository.MemoryRepositoryImpl
import com.memorymap.data.repository.OnThisDayRepositoryImpl
import com.memorymap.data.repository.ReferenceRepositoryImpl
import com.memorymap.data.repository.SearchRepositoryImpl
import com.memorymap.data.repository.SyncRepositoryImpl
import com.memorymap.data.repository.UserRepositoryImpl
import com.memorymap.domain.map.MapProvider
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.BackupRepository
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.domain.repository.MediaRepository
import com.memorymap.domain.repository.MemoryRepository
import com.memorymap.domain.repository.OnThisDayRepository
import com.memorymap.domain.repository.ReferenceRepository
import com.memorymap.domain.repository.SearchRepository
import com.memorymap.domain.repository.SyncRepository
import com.memorymap.domain.repository.UserRepository
import com.memorymap.util.AndroidImageOptimizer
import com.memorymap.util.ImageOptimizer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jan.supabase.auth.SessionManager
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
    abstract fun bindMediaRepository(impl: MediaRepositoryImpl): MediaRepository

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

    /**
     * The remote half of synchronisation.
     *
     * Bound behind its interface so the repository — and every test — depends on
     * `SyncApi` and never on Postgrest.
     */
    @Binds
    @Singleton
    abstract fun bindSyncApi(impl: PostgrestSyncApi): SyncApi

    /** The bucket, behind its interface, for the same reason `SyncApi` is. */
    @Binds
    @Singleton
    abstract fun bindMediaStorage(impl: SupabaseMediaStorage): MediaStorage

    /** Attachments' bytes on this device, so the sync layer needs no context. */
    @Binds
    @Singleton
    abstract fun bindMediaFileStore(impl: LocalMediaFileStore): MediaFileStore

    /**
     * What a photo looks like by the time it is uploaded.
     *
     * Behind its interface for the same reason the bucket is: the sync tests
     * hand the uploader a stand-in and check that it sends what it was given,
     * which is a claim no decoder is needed to prove.
     */
    @Binds
    @Singleton
    abstract fun bindImageOptimizer(impl: AndroidImageOptimizer): ImageOptimizer

    @Binds
    @Singleton
    abstract fun bindSearchRepository(impl: SearchRepositoryImpl): SearchRepository

    @Binds
    @Singleton
    abstract fun bindBackupRepository(impl: BackupRepositoryImpl): BackupRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(impl: SyncRepositoryImpl): SyncRepository

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: SupabaseAuthRepository): AuthRepository

    companion object {

        @Provides
        @Singleton
        fun provideSupabaseConfig(): SupabaseConfig = SupabaseConfig.fromBuildConfig()

        /**
         * The tile source. It comes from the build settings so the app is never
         * hard-wired to one host, and a malformed setting falls back to
         * OpenStreetMap instead of drawing nothing.
         */
        @Provides
        @Singleton
        fun provideMapProvider(): MapProvider = MapProviders.fromConfig(
            tileTemplate = BuildConfig.MAP_TILE_SERVER,
            attribution = BuildConfig.MAP_ATTRIBUTION,
        )

        @Provides
        @Singleton
        fun provideSessionManager(store: SecureSessionStore): SessionManager = store

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
