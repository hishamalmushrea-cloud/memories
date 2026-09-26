package com.memorymap.ui.profile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.repository.LifeStatsCalculator
import com.memorymap.domain.model.CloudRemoval
import com.memorymap.domain.model.WipeSummary
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.testing.FakeAuthRepository
import com.memorymap.testing.FakeSyncRepository
import com.memorymap.testing.RecordingUserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The profile screen's destructive actions, in the order they must happen.
 *
 * Deleting the account is the one action in the app that cannot be undone by
 * anyone, including the user, so the interesting cases are the ones where half
 * of it happens: a server that refuses, a request that was never possible. The
 * rule under test is that the device is wiped only after the server has
 * confirmed, because a device emptied on the strength of a failed request leaves
 * the user with nothing locally and an account still holding everything.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MemoryMapDatabase
    private lateinit var auth: FakeAuthRepository
    private lateinit var users: RecordingUserRepository

    private val userId = "user-1"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        auth = FakeAuthRepository(userId)
        users = RecordingUserRepository()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel() = ProfileViewModel(
        statsCalculator = LifeStatsCalculator(
            memoryDao = db.memoryDao(),
            entryDao = db.dailyEntryDao(),
            mediaDao = db.mediaDao(),
            placeDao = db.placeDao(),
            personDao = db.personDao(),
        ),
        authRepository = auth,
        syncRepository = FakeSyncRepository(),
        userRepository = users,
    )

    @Test
    fun `opening the wipe confirmation counts the uploaded attachments`() = runTest {
        users.uploadedCount = 4
        val vm = viewModel()

        vm.requestDeleteLocalData()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.state.value.wipeConfirmationVisible)
        assertEquals(4, vm.state.value.uploadedCount)

        vm.cancelDeleteLocalData()
        assertFalse(vm.state.value.wipeConfirmationVisible)
        assertEquals(0, vm.state.value.uploadedCount)
    }

    @Test
    fun `the account confirmation is opened and can be cancelled`() = runTest {
        val vm = viewModel()

        vm.requestDeleteAccount()
        assertTrue(vm.state.value.accountConfirmationVisible)

        vm.cancelDeleteAccount()
        assertFalse(vm.state.value.accountConfirmationVisible)
        assertEquals(0, auth.deleteAccountCalls)
    }

    @Test
    fun `a refused deletion deletes nothing on this device`() = runTest {
        auth.deletionResult = AuthRepository.Deletion.FAILED
        val vm = viewModel()

        vm.confirmDeleteAccount()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthRepository.Deletion.FAILED, vm.state.value.accountDeletion)
        // The uploads could be removed, since they belong to an account that was
        // going to be deleted; the archive on the device could not, because the
        // account is still there and the user may still want it.
        assertEquals(listOf("deleteCloudCopies($userId)"), users.calls)
        assertNull(vm.state.value.wipeSummary)
        assertFalse(vm.state.value.isDeletingAccount)
        assertEquals(userId, auth.currentUserId.value)
    }

    @Test
    fun `a deletion with no project connected deletes nothing at all`() = runTest {
        auth.deletionResult = AuthRepository.Deletion.NOT_CONFIGURED
        val vm = viewModel()

        vm.confirmDeleteAccount()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthRepository.Deletion.NOT_CONFIGURED, vm.state.value.accountDeletion)
        assertTrue("no bucket can be reached without a project", users.calls.isEmpty())
        assertNull(vm.state.value.wipeSummary)
    }

    @Test
    fun `a refusal after the uploads were removed reports them instead of claiming nothing went`() = runTest {
        auth.deletionResult = AuthRepository.Deletion.FAILED
        users.cloudRemoval = CloudRemoval(removed = 2)
        val vm = viewModel()

        vm.confirmDeleteAccount()
        dispatcher.scheduler.advanceUntilIdle()

        // The uploads went first, so they are gone even though the account is
        // not; the screen reports the count rather than saying "nothing was
        // deleted", which would be false.
        assertEquals(CloudRemoval(removed = 2), vm.state.value.cloudRemoval)
        assertEquals(AuthRepository.Deletion.FAILED, vm.state.value.accountDeletion)
        assertNull(vm.state.value.wipeSummary)
    }

    @Test
    fun `a confirmed deletion wipes the device and shows what went`() = runTest {
        auth.deletionResult = AuthRepository.Deletion.DELETED
        val vm = viewModel()

        vm.confirmDeleteAccount()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AuthRepository.Deletion.DELETED, vm.state.value.accountDeletion)
        // The order is the guarantee: the uploads go while the rows that name
        // them still exist, then the server, then the device.
        assertEquals(
            listOf("deleteCloudCopies($userId)", "deleteLocalData($userId)"),
            users.calls,
        )
        assertEquals(WipeSummary(memories = 3, entries = 2, mediaFiles = 1), vm.state.value.wipeSummary)
        assertNull(auth.currentUserId.value)
        assertFalse(vm.state.value.isDeletingAccount)
    }

    @Test
    fun `asking to delete the server records does it and reports the answer`() = runTest {
        users.serverRecordsRemoved = false
        val vm = viewModel()

        vm.confirmDeleteLocalData(deleteServerRecords = true)
        dispatcher.scheduler.advanceUntilIdle()

        // The uploaded files go even though that box was not ticked: their keys
        // are on the rows the server is about to drop, so keeping them would
        // leave objects nothing can name.
        assertEquals(
            listOf("deleteCloudCopies($userId)", "deleteServerRecords", "deleteLocalData($userId)"),
            users.calls,
        )
        assertEquals(false, vm.state.value.serverRecordsRemoved)
        assertEquals(WipeSummary(memories = 3, entries = 2, mediaFiles = 1), vm.state.value.wipeSummary)
        // The local wipe happened anyway: an unreachable server must not be able
        // to keep the archive on the user's own device.
        assertNull(auth.currentUserId.value)
    }

    @Test
    fun `deleting local data only never touches the server or the bucket`() = runTest {
        val vm = viewModel()

        vm.confirmDeleteLocalData()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("deleteLocalData($userId)"), users.calls)
        assertNull(vm.state.value.serverRecordsRemoved)
        // Neither box was ticked, so nothing on the server was touched.
        assertNull(vm.state.value.cloudRemoval)
    }

    @Test
    fun `the summary can be dismissed and stops being shown`() = runTest {
        val vm = viewModel()

        vm.confirmDeleteLocalData()
        dispatcher.scheduler.advanceUntilIdle()
        vm.dismissWipeSummary()

        assertNull(vm.state.value.wipeSummary)
        assertNull(vm.state.value.cloudRemoval)
        assertNull(vm.state.value.serverRecordsRemoved)
    }
}
