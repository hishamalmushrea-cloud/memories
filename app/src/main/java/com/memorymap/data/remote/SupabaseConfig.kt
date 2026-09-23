package com.memorymap.data.remote

import com.memorymap.BuildConfig

/**
 * Client-side Supabase settings.
 *
 * Security rules that are enforced by this class:
 *  - Only the anon (publishable) key is ever read; the `service_role` key must
 *    never appear in an Android build because everything in the APK is public.
 *  - When no key is configured the app still runs, in offline-only mode. That is
 *    the default state of a fresh install and of a debug build.
 */
data class SupabaseConfig(
    val url: String,
    val anonKey: String,
) {
    val isConfigured: Boolean
        get() = url.isNotBlank() && anonKey.isNotBlank()

    companion object {
        /** Reads the values injected at build time from local.properties / env. */
        fun fromBuildConfig(): SupabaseConfig = SupabaseConfig(
            url = BuildConfig.SUPABASE_URL.trim(),
            anonKey = BuildConfig.SUPABASE_ANON_KEY.trim(),
        )
    }
}
