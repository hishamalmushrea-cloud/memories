package com.memorymap.ui.backup

import com.memorymap.domain.repository.BackupOutcome
import com.memorymap.testing.FakeAuthRepository
import com.memorymap.testing.RecordingBackupRepository
import com.memorymap.util.backup.BackupCounts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

/**
 * Exporting and importing the archive.
 *
 * The one rule this screen exists for: **an import never runs the moment a folder is
 * picked.** Merging an archive into a life is a decision a person makes with the numbers
 * in front of them, so the folder is inspected, the archive is validated, and only then
 * does a confirmation appear. Every test below is one of those steps either happening or
 * being refused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        repository: RecordingBackupRepository = RecordingBackupRepository(),
        userId: String? = "user-1",
    ) = BackupViewModel(repository, FakeAuthRepository(userId))

    @Test
    fun `with no signed-in account an export does nothing`() = runTest {
        val repository = RecordingBackupRepository()
        val model = viewModel(repository, userId = null)

        model.export("content://picked/folder")
        advanceUntilIdle()

        assertTrue(repository.calls.isEmpty())
        assertFalse(model.state.value.isBusy)
    }

    @Test
    fun `a finished export reports what it wrote`() = runTest {
        val repository = RecordingBackupRepository(
            exportOutcome = BackupOutcome.Exported(
                manifest = RecordingBackupRepository.manifest(),
                mediaCopied = 4,
                mediaMissing = 1,
            ),
        )
        val model = viewModel(repository)

        model.export("content://picked/folder")
        advanceUntilIdle()

        val state = model.state.value
        assertEquals("backup_exported", state.messageKey)
        assertFalse(state.isBusy)
        assertEquals(3, state.lastExport?.memories)
        assertEquals(4, state.mediaCopied)
        // An attachment whose file is gone is reported rather than silently dropped: the
        // archive exists, and the person needs to know it is not a full copy.
        assertEquals(1, state.mediaMissing)
        assertNull(state.lastImport)
    }

    @Test
    fun `a failed export keeps the reason the repository gave`() = runTest {
        val repository = RecordingBackupRepository(
            exportOutcome = BackupOutcome.Failed("backup_error_no_space"),
        )
        val model = viewModel(repository)

        model.export("content://picked/folder")
        advanceUntilIdle()

        assertEquals("backup_error_no_space", model.state.value.messageKey)
        assertNull(model.state.value.lastExport)
        assertFalse(model.state.value.isBusy)
    }

    @Test
    fun `a folder that holds no archive is reported as a format problem`() = runTest {
        val repository = RecordingBackupRepository(manifestToInspect = null)
        val model = viewModel(repository)

        model.inspect("content://picked/empty")
        advanceUntilIdle()

        assertEquals("backup_error_format", model.state.value.messageKey)
        assertNull(model.state.value.pendingManifest)
        assertNull(model.state.value.pendingUri)
    }

    @Test
    fun `an archive written by a newer app is refused before it is merged`() = runTest {
        val repository = RecordingBackupRepository(
            // The format version is what a future build would raise; merging half of an
            // archive whose shape is unknown is worse than refusing it.
            manifestToInspect = RecordingBackupRepository.manifest(formatVersion = 99),
        )
        val model = viewModel(repository)

        model.inspect("content://picked/future")
        advanceUntilIdle()

        assertEquals("backup_error_format", model.state.value.messageKey)
        assertNull(model.state.value.pendingManifest)
    }

    @Test
    fun `a valid archive waits for the person to confirm it`() = runTest {
        val repository = RecordingBackupRepository()
        val model = viewModel(repository)

        model.inspect("content://picked/folder")
        advanceUntilIdle()

        val state = model.state.value
        assertEquals("content://picked/folder", state.pendingUri)
        assertEquals(3, state.pendingManifest?.counts?.memories)
        assertFalse(state.isBusy)
        // Nothing has been merged: inspecting is a read.
        assertTrue(repository.calls.none { it.startsWith("import(") })
    }

    @Test
    fun `confirming without an archive at hand does nothing`() = runTest {
        val repository = RecordingBackupRepository()
        val model = viewModel(repository)

        model.confirmImport()
        advanceUntilIdle()

        assertTrue(repository.calls.isEmpty())
        assertFalse(model.state.value.isBusy)
    }

    @Test
    fun `a confirmed import reports what it merged`() = runTest {
        val repository = RecordingBackupRepository(
            importOutcome = BackupOutcome.Imported(
                counts = BackupCounts(memories = 2, dailyEntries = 5, people = 1, places = 0),
                mediaRestored = 2,
                skipped = 3,
            ),
        )
        val model = viewModel(repository)

        model.inspect("content://picked/folder")
        advanceUntilIdle()
        model.confirmImport()
        advanceUntilIdle()

        val state = model.state.value
        assertEquals("backup_imported", state.messageKey)
        assertEquals(2, state.lastImport?.memories)
        assertEquals(2, state.mediaRestored)
        // Skipped rows are the ones this device already had newer: an import is a merge
        // and must never count them as imported or as lost.
        assertEquals(3, state.skipped)
        assertNull(state.pendingUri)
        assertNull(state.pendingManifest)
    }

    @Test
    fun `cancelling clears the archive that was waiting`() = runTest {
        val repository = RecordingBackupRepository()
        val model = viewModel(repository)

        model.inspect("content://picked/folder")
        advanceUntilIdle()
        model.cancelImport()

        assertNull(model.state.value.pendingUri)
        assertNull(model.state.value.pendingManifest)
        assertTrue(repository.calls.none { it.startsWith("import(") })
    }

    @Test
    fun `a failed import keeps the reason`() = runTest {
        val repository = RecordingBackupRepository(
            importOutcome = BackupOutcome.Failed("backup_error_corrupt"),
        )
        val model = viewModel(repository)

        model.inspect("content://picked/folder")
        advanceUntilIdle()
        model.confirmImport()
        advanceUntilIdle()

        assertEquals("backup_error_corrupt", model.state.value.messageKey)
        assertNull(model.state.value.lastImport)
    }

    @Test
    fun `the message can be dismissed`() = runTest {
        val model = viewModel()

        model.export("content://picked/folder")
        advanceUntilIdle()
        assertEquals("backup_exported", model.state.value.messageKey)

        model.dismissMessage()

        assertNull(model.state.value.messageKey)
    }
}
