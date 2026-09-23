package com.memorymap.data.remote

import com.memorymap.util.MmLog
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the Supabase client and exposes whether cloud features are usable.
 *
 * The app is offline-first: when [isAvailable] is false every feature still
 * works against Room, and the sync worker simply stays idle.
 */
@Singleton
class SupabaseClientProvider @Inject constructor(
    private val config: SupabaseConfig,
) {

    val isAvailable: Boolean get() = config.isConfigured

    private var cached: SupabaseClient? = null

    /**
     * Returns the shared client, or null when the project is not configured.
     * The client is created lazily so an unconfigured build never opens a
     * connection or performs a network handshake.
     */
    fun get(): SupabaseClient? {
        if (!config.isConfigured) return null
        cached?.let { return it }
        return try {
            createSupabaseClient(supabaseUrl = config.url, supabaseKey = config.anonKey) {
                install(Auth)
                install(Postgrest)
                install(Storage)
            }.also { cached = it }
        } catch (error: Throwable) {
            // Never log the key or the URL: both are project configuration.
            MmLog.e("Unable to create the Supabase client", error)
            null
        }
    }

    /** Storage bucket names, created by `supabase/schema.sql`. */
    object Buckets {
        const val MEDIA = "media"
        const val AVATARS = "avatars"
    }
}
