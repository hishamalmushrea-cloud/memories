package com.memorymap.data.repository

import com.memorymap.data.local.LockSettings
import com.memorymap.domain.model.LockState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The lock state machine, driven directly - no Android, no dispatcher.
 *
 * The interesting cases are the ones where the two inputs could disagree: the
 * setting says the lock is on but the session says it was passed, or the setting
 * is turned off while it was passed. The rule under test is that the state is
 * always recomputed from both, so the app is never "unlocked" after the lock was
 * turned off, and never inherits a pass from before it was turned back on.
 */
class AppLockRepositoryTest {

    /** The persistence port, in memory, so the machine runs on the plain JVM. */
    private class FakeLockSettings(override var enabled: Boolean = false) : LockSettings

    @Test
    fun `a fresh install with the lock off is Disabled`() = runTest {
        val repo = AppLockRepositoryImpl(FakeLockSettings())
        assertEquals(LockState.Disabled, repo.lockState.first())
    }

    @Test
    fun `turning the lock on starts Locked, not Unlocked`() = runTest {
        val repo = AppLockRepositoryImpl(FakeLockSettings())
        repo.setEnabled(true)
        assertEquals(LockState.Locked, repo.lockState.first())
    }

    @Test
    fun `a confirmed credential unlocks`() = runTest {
        val repo = AppLockRepositoryImpl(FakeLockSettings(enabled = true))
        repo.onUnlockConfirmed()
        assertEquals(LockState.Unlocked, repo.lockState.first())
    }

    @Test
    fun `locking again after an unlock asks once more`() = runTest {
        val repo = AppLockRepositoryImpl(FakeLockSettings(enabled = true))
        repo.onUnlockConfirmed()
        repo.lock()
        assertEquals(LockState.Locked, repo.lockState.first())
    }

    @Test
    fun `turning the lock off disables it even while unlocked`() = runTest {
        val repo = AppLockRepositoryImpl(FakeLockSettings(enabled = true))
        repo.onUnlockConfirmed()
        repo.setEnabled(false)
        assertEquals(LockState.Disabled, repo.lockState.first())
    }

    @Test
    fun `turning the lock back on does not inherit the earlier pass`() = runTest {
        val settings = FakeLockSettings()
        val repo = AppLockRepositoryImpl(settings)
        repo.setEnabled(true)
        repo.onUnlockConfirmed()
        repo.setEnabled(false)
        repo.setEnabled(true)
        // The pass was given up when the lock was turned off, so it starts locked.
        assertEquals(LockState.Locked, repo.lockState.first())
    }

    @Test
    fun `locking while disabled stays Disabled`() = runTest {
        val repo = AppLockRepositoryImpl(FakeLockSettings())
        repo.lock()
        assertEquals(LockState.Disabled, repo.lockState.first())
    }

    @Test
    fun `the choice survives a restart because the setting is persisted`() = runTest {
        val settings = FakeLockSettings()
        AppLockRepositoryImpl(settings).setEnabled(true)
        // A new instance reading the same store - what a process restart is.
        val reopened = AppLockRepositoryImpl(settings)
        assertEquals(LockState.Locked, reopened.lockState.first())
    }
}
