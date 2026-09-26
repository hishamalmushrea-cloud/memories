package com.memorymap.security

import com.memorymap.data.remote.SupabaseConfig
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.Visibility
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacy as a default rather than as an option the user has to find.
 *
 * §46 is explicit that a diary entry is private and that PUBLIC must never be
 * the default. The strongest form of that is what the model does: a memory has
 * to be deliberately opened up, and a diary entry has no visibility to set.
 */
class PrivacyDefaultsTest {

    @Test
    fun `a new memory is private unless the user says otherwise`() {
        val memory = Memory(userId = "u1", title = "t", memoryDate = LocalDate.of(2024, 3, 1))

        assertEquals(Visibility.PRIVATE, memory.visibility)
    }

    @Test
    fun `a diary entry has no visibility to set at all`() {
        // A data class property generates an accessor, so the absence of one
        // means the concept is absent from the type, not merely defaulted.
        val accessors = DailyEntry::class.java.declaredMethods.map { it.name }

        assertFalse(
            "the diary must not be shareable, but found: $accessors",
            accessors.any { it.equals("getVisibility", ignoreCase = true) },
        )
    }

    @Test
    fun `a memory does have a visibility, so the two are genuinely different`() {
        val accessors = Memory::class.java.declaredMethods.map { it.name }

        assertTrue(accessors.any { it.equals("getVisibility", ignoreCase = true) })
    }

    @Test
    fun `an https endpoint with a key is configured`() {
        val config = SupabaseConfig(url = "https://example.supabase.co", anonKey = "anon")

        assertTrue(config.isHttps)
        assertTrue(config.isConfigured)
    }

    @Test
    fun `a plain http endpoint is treated as not configured`() {
        // §39 requires HTTPS. Silently talking to an insecure project would put
        // the session token and the diary on the wire in the clear, so the app
        // stays offline instead.
        val config = SupabaseConfig(url = "http://example.supabase.co", anonKey = "anon")

        assertFalse(config.isHttps)
        assertFalse(config.isConfigured)
    }

    @Test
    fun `a url with no scheme is treated as not configured`() {
        val config = SupabaseConfig(url = "example.supabase.co", anonKey = "anon")

        assertFalse(config.isConfigured)
    }

    @Test
    fun `https without a key is not configured`() {
        // The normal state of a fresh install: fully working, offline only.
        val config = SupabaseConfig(url = "https://example.supabase.co", anonKey = "")

        assertTrue(config.isHttps)
        assertFalse(config.isConfigured)
    }

    @Test
    fun `the scheme check ignores case and surrounding space is trimmed by the caller`() {
        assertTrue(SupabaseConfig("HTTPS://example.supabase.co", "anon").isHttps)
    }

    @Test
    fun `a diary entry defaults to a pending create so it is queued, not assumed uploaded`() {
        val entry = DailyEntry(
            userId = "u1",
            date = LocalDate.of(2024, 3, 1),
            time = LocalDateTime.of(2024, 3, 1, 21, 40),
            title = "t",
        )

        assertEquals(com.memorymap.domain.model.SyncStatus.PENDING_CREATE, entry.syncStatus)
    }
}
