package com.memorymap

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.memorymap.data.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * Application entry point.
 *
 * It hands WorkManager a Hilt-aware worker factory, schedules the offline-queue
 * sync, and configures the image loader that serves both photos and map tiles.
 * No analytics, no crash reporting, no third-party SDK initialisation.
 */
@HiltAndroidApp
class MemoryMapApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        SyncScheduler.schedule(this)
    }

    /**
     * One image loader for photos and map tiles.
     *
     * The disk cache is what makes the map affordable: panning back over ground
     * already seen costs no network, which matters because public tile servers
     * are neither unlimited nor free. The interceptor sends a real user agent,
     * which OpenStreetMap's tile usage policy requires and which also lets a tile
     * host recognise and talk to the app instead of blocking anonymous traffic.
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", USER_AGENT)
                        .build()
                    chain.proceed(request)
                }
                .build()
        }
        .diskCache(
            DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache").toOkioPath())
                .maxSizeBytes(TILE_CACHE_BYTES)
                .build(),
        )
        .respectCacheHeaders(true)
        .crossfade(false)
        .build()

    private companion object {
        /** 100 MB of tiles and thumbnails; older entries are evicted by Coil. */
        const val TILE_CACHE_BYTES = 100L * 1024 * 1024

        /** Identifies the app to tile hosts, as their usage policies ask. */
        val USER_AGENT = "MemoryMap/${BuildConfig.VERSION_NAME} (offline-first personal archive)"
    }
}
