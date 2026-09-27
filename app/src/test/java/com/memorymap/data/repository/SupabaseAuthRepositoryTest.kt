package com.memorymap.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.remote.SupabaseClientProvider
import com.memorymap.data.remote.SupabaseConfig
import com.memorymap.domain.model.AuthState
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.LOCAL_USER_ID
import com.memorymap.testing.RecordingAccountApi
import io.github.jan.supabase.auth.MemorySessionManager
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 2: authentication against the offline path, which is what a fresh
 * install (no Supabase project configured) actually uses. The Supabase network
 * calls are exercised on a device, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SupabaseAuthRepositoryTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var repository: SupabaseAuthRepository

    /**
     * The two server-side deletions, recorded rather than sent.
     *
     * Nothing here reaches a server: the unconfigured tests below must be able to
     * prove that a deletion with no project never asks the network for anything.
     */
    private val accountApi = RecordingAccountApi()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()

        // An unconfigured provider, so isCloudConfigured is false and every call
        // takes the local path.
        val provider = SupabaseClientProvider(SupabaseConfig(url = "", anonKey = ""), MemorySessionManager())
        repository = SupabaseAuthRepository(provider, db, accountApi)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `restore with no account leaves the user signed out`() = runTest {
        repository.restoreSession()
        assertEquals(AuthState.SignedOut, repository.authState.value)
        assertNull(repository.currentUserId.value)
    }

    @Test
    fun `continuing offline creates the local account and signs in`() = runTest {
        val user = repository.continueOffline("هشام")

        assertEquals(LOCAL_USER_ID, user.id)
        assertEquals(LOCAL_USER_ID, repository.currentUserId.value)
        val state = repository.authState.value
        assertTrue(state is AuthState.SignedIn)
        assertTrue((state as AuthState.SignedIn).offlineAccount)
        assertEquals(LOCAL_USER_ID, db.userDao().getById(LOCAL_USER_ID)?.id)
    }

    @Test
    fun `a blank offline name falls back to the default display name`() = runTest {
        val user = repository.continueOffline("   ")
        assertTrue(user.displayName.isNotBlank())
    }

    @Test
    fun `restoring after an offline sign-in brings the local account back`() = runTest {
        repository.continueOffline("هشام")

        // Simulate a fresh repository over the same database, as after a restart.
        val provider = SupabaseClientProvider(SupabaseConfig("", ""), MemorySessionManager())
        val restored = SupabaseAuthRepository(provider, db, accountApi)
        restored.restoreSession()

        assertEquals(LOCAL_USER_ID, restored.currentUserId.value)
        assertTrue(restored.authState.value is AuthState.SignedIn)
    }

    @Test
    fun `local sign in matches the stored email ignoring case`() = runTest {
        repository.localSignUpForTest("Hisham@Example.com", "هشام")

        val wrong = repository.signIn("someone@else.com", "ignored-offline")
        assertTrue(wrong is com.memorymap.domain.repository.AuthRepository.Result.Failure)

        val right = repository.signIn("hisham@example.com", "ignored-offline")
        assertTrue(right is com.memorymap.domain.repository.AuthRepository.Result.Success)
    }

    @Test
    fun `sign out clears the session but keeps the local archive`() = runTest {
        repository.continueOffline("هشام")

        repository.signOut()

        assertEquals(AuthState.SignedOut, repository.authState.value)
        assertNull(repository.currentUserId.value)
        // The profile row must survive so the diary is still there on return.
        assertEquals(LOCAL_USER_ID, db.userDao().getById(LOCAL_USER_ID)?.id)
    }

    @Test
    fun `password reset without a server reports the offline error`() = runTest {
        val result = repository.resetPassword("hisham@example.com")
        assertEquals(
            com.memorymap.domain.repository.AuthRepository.Result.Failure("auth_error_offline"),
            result,
        )
    }

    @Test
    fun `sign up without a server creates a local account`() = runTest {
        val result = repository.signUp("hisham@example.com", "does-not-matter", "هشام")
        assertTrue(result is com.memorymap.domain.repository.AuthRepository.Result.Success)
        assertEquals(LOCAL_USER_ID, repository.currentUserId.value)
        assertFalse(repository.isCloudConfigured)
    }

    // Exposes the private local sign-up so the case-insensitive match is testable.
    private suspend fun SupabaseAuthRepository.localSignUpForTest(email: String, name: String) {
        signUp(email, "offline", name)
    }

    // --- deleting the account ---

    /**
     * A repository over a *configured* project with the server replaced.
     *
     * The client is built against a URL that is never contacted: the deletion
     * itself is the double's job, and with no stored session the local sign-out
     * has nothing to talk to either. What is being tested is the decision, not
     * Postgrest.
     */
    private fun configuredRepository(accountApi: RecordingAccountApi): SupabaseAuthRepository {
        val provider = SupabaseClientProvider(
            SupabaseConfig(url = "https://project.supabase.co", anonKey = "anon-key"),
            MemorySessionManager(),
        )
        return SupabaseAuthRepository(provider, db, accountApi)
    }

    @Test
    fun `deleting the account without a project says so and keeps the local account`() = runTest {
        repository.continueOffline("هشام")

        val result = repository.deleteAccount()

        assertEquals(AuthRepository.Deletion.NOT_CONFIGURED, result)
        // The server was never asked, and the claim would be false here: there is
        // no server account to delete, and the offline account is the user's only
        // archive.
        assertEquals(0, accountApi.accountDeletions)
        assertEquals(LOCAL_USER_ID, repository.currentUserId.value)
        assertEquals(LOCAL_USER_ID, db.userDao().getById(LOCAL_USER_ID)?.id)
    }

    @Test
    fun `a deletion the server refused reports the failure and changes nothing`() = runTest {
        val api = RecordingAccountApi(failing = true)
        val configured = configuredRepository(api)
        configured.continueOffline("هشام")

        val result = configured.deleteAccount()

        assertEquals(AuthRepository.Deletion.FAILED, result)
        assertEquals(1, api.accountDeletions)
        // Not signed out: the account still exists, so the session must too.
        assertTrue(configured.authState.value is AuthState.SignedIn)
        assertEquals(LOCAL_USER_ID, configured.currentUserId.value)
        assertNotNull(db.userDao().getById(LOCAL_USER_ID))
    }

    @Test
    fun `a confirmed deletion ends the session on this device`() = runTest {
        val api = RecordingAccountApi()
        val configured = configuredRepository(api)
        configured.continueOffline("هشام")

        val result = configured.deleteAccount()

        assertEquals(AuthRepository.Deletion.DELETED, result)
        assertEquals(1, api.accountDeletions)
        assertEquals(AuthState.SignedOut, configured.authState.value)
        assertNull(configured.currentUserId.value)
        // The row itself goes with the wipe that follows, not with this call:
        // the caller deletes the device only after this answer.
        assertNotNull(db.userDao().getById(LOCAL_USER_ID))
    }
}
