package com.memorymap.ui.memories

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.memorymap.R
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.repository.MediaRepositoryImpl
import com.memorymap.data.repository.MemoryRepositoryImpl
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.Memory
import com.memorymap.domain.repository.MediaRepository
import com.memorymap.domain.repository.MemoryRepository
import com.memorymap.testing.FakeAuthRepository
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The memory detail screen: one memory, its attachments, and the two deletes.
 *
 * It has the shape the list has - a `stateIn` of a combine, with the message kept in
 * a separate flow so a refresh cannot wipe it - and the failures it reports were the
 * last ones added to the app, which is why they are tested here rather than assumed.
 *
 * Room runs its suspend queries on an executor the test scheduler cannot fast-forward,
 * so every assertion waits on the state stream rather than on the clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryDetailViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MemoryMapDatabase
    private lateinit var memories: MemoryRepositoryImpl
    private lateinit var media: MediaRepositoryImpl

    private val userId = "user-1"

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        memories = MemoryRepositoryImpl(db.memoryDao())
        media = MediaRepositoryImpl(db.mediaDao())
    }

    @After
    fun tearDown() {
        // The dispatcher goes back before the database closes; see MemoriesViewModelTest
        // for why the order matters when a view model is still collecting.
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `the screen shows the memory and its attachments`() = runTest {
        val saved = memory("رحلة إلى عدن")
        memories.save(saved)
        media.attach(attachment(saved.id, "صورة.jpg"))

        val viewModel = viewModel(saved.id)

        viewModel.state.test {
            val loaded = awaitWhere { !it.isLoading }
            assertEquals("رحلة إلى عدن", loaded.memory?.title)
            assertEquals(1, loaded.attachments.size)
            assertEquals("صورة.jpg", File(loaded.attachments.first().uri).name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A tombstone is not a memory.
     *
     * The stream still carries the row after a delete - that is what a tombstone is -
     * so the screen has to decide, and it decides gone. Showing it would mean the
     * person sees a memory they deleted come back on the next edit of it.
     */
    @Test
    fun `a memory deleted elsewhere reads as gone rather than as itself`() = runTest {
        val saved = memory("سيُحذف من مكان آخر")
        memories.save(saved)

        val viewModel = viewModel(saved.id)

        viewModel.state.test {
            awaitWhere { it.memory != null }

            memories.delete(saved.id)

            val afterDelete = awaitWhere { it.isDeleted }
            assertNull(afterDelete.memory)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting takes the memory and its attachment files with it`() = runTest {
        val saved = memory("مع مرفق")
        memories.save(saved)
        val file = temporaryFile("سيُحذف.jpg")
        media.attach(attachment(saved.id, file.name, file.absolutePath))

        val viewModel = viewModel(saved.id)
        viewModel.state.test {
            awaitWhere { it.attachments.size == 1 }
            assertEquals(1, db.mediaDao().getFor(MediaOwner.MEMORY.name, saved.id).size)

            viewModel.delete()
            awaitWhere { it.isDeleted }

            // The row is a tombstone, the file is gone, and the attachment list is empty.
            assertNotNull(db.memoryDao().getById(saved.id)?.deletedAt)
            assertTrue(db.mediaDao().getFor(MediaOwner.MEMORY.name, saved.id).isEmpty())
            assertTrue("the bytes should not outlive the row", !file.exists())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a delete that fails says so and keeps the memory`() = runTest {
        val saved = memory("لن تُحذف")
        memories.save(saved)

        val viewModel = MemoryDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("memoryId" to saved.id)),
            memoryRepository = FailingDeleteMemoryRepository(memories),
            mediaRepository = media,
            authRepository = FakeAuthRepository(userId),
        )

        viewModel.state.test {
            awaitWhere { it.memory != null }
            viewModel.delete()

            val failed = awaitWhere { it.errorRes != null }
            assertEquals(R.string.memory_error_delete, failed.errorRes)
            assertNull(db.memoryDao().getById(saved.id)?.deletedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `removing one attachment leaves the others alone`() = runTest {
        val saved = memory("مرفقان")
        memories.save(saved)
        val keep = attachment(saved.id, "يبقى.jpg")
        val remove = attachment(saved.id, "يُحذف.jpg")
        media.attach(keep)
        media.attach(remove)

        val viewModel = viewModel(saved.id)
        viewModel.state.test {
            val loaded = awaitWhere { it.attachments.size == 2 }

            viewModel.removeAttachment(loaded.attachments.first { it.id == remove.id })

            val after = awaitWhere { it.attachments.size == 1 }
            assertEquals(keep.id, after.attachments.single().id)
            assertNull(after.errorRes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an attachment that cannot be removed says so and stays`() = runTest {
        val saved = memory("مرفق عنيد")
        memories.save(saved)
        val stubborn = attachment(saved.id, "عنيد.jpg")
        media.attach(stubborn)

        val viewModel = MemoryDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("memoryId" to saved.id)),
            memoryRepository = memories,
            mediaRepository = FailingRemoveMediaRepository(media),
            authRepository = FakeAuthRepository(userId),
        )

        viewModel.state.test {
            val loaded = awaitWhere { it.attachments.size == 1 }
            viewModel.removeAttachment(loaded.attachments.single())

            val failed = awaitWhere { it.errorRes != null }
            assertEquals(R.string.memory_error_media, failed.errorRes)
            // Still there, which is what makes the message worth showing.
            assertEquals(1, failed.attachments.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** Dismissing must clear the message without touching anything else. */
    @Test
    fun `dismissing the message leaves the memory alone`() = runTest {
        val saved = memory("رسالة تُغلق")
        memories.save(saved)

        val viewModel = MemoryDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("memoryId" to saved.id)),
            memoryRepository = FailingDeleteMemoryRepository(memories),
            mediaRepository = media,
            authRepository = FakeAuthRepository(userId),
        )

        viewModel.state.test {
            awaitWhere { it.memory != null }
            viewModel.delete()
            awaitWhere { it.errorRes != null }

            viewModel.clearError()

            val cleared = awaitWhere { it.errorRes == null }
            assertEquals("رسالة تُغلق", cleared.memory?.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a signed-out screen shows nothing and reports nothing`() = runTest {
        val saved = memory("لمن لا حساب")
        memories.save(saved)

        val viewModel = MemoryDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("memoryId" to saved.id)),
            memoryRepository = memories,
            mediaRepository = media,
            authRepository = FakeAuthRepository(userId = null),
        )

        viewModel.state.test {
            val state = awaitWhere { !it.isLoading }
            assertNull(state.memory)
            assertTrue(state.attachments.isEmpty())
            assertNull(state.errorRes)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the editor is offered the id the screen was opened with`() = runTest {
        val viewModel = viewModel("memory-42")
        assertEquals("memory-42", viewModel.id)
    }

    private fun viewModel(memoryId: String) = MemoryDetailViewModel(
        savedStateHandle = SavedStateHandle(mapOf("memoryId" to memoryId)),
        memoryRepository = memories,
        mediaRepository = media,
        authRepository = FakeAuthRepository(userId),
    )

    private fun memory(title: String) = Memory(
        userId = userId,
        title = title,
        memoryDate = LocalDate.of(2026, 9, 23),
    )

    private fun attachment(ownerId: String, name: String, path: String? = null) = MediaItem(
        ownerType = MediaOwner.MEMORY,
        ownerId = ownerId,
        type = MediaType.PHOTO,
        uri = path ?: temporaryFile(name).absolutePath,
        mimeType = "image/jpeg",
    )

    /** A real file, because the delete path deletes one. */
    private fun temporaryFile(name: String): File =
        File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, name)
            .apply { writeBytes(ByteArray(16)) }

    /** Skips intermediate emissions until one satisfies [predicate]. */
    private suspend fun ReceiveTurbine<MemoryDetailUiState>.awaitWhere(
        predicate: (MemoryDetailUiState) -> Boolean,
    ): MemoryDetailUiState {
        var state = awaitItem()
        while (!predicate(state)) {
            state = awaitItem()
        }
        return state
    }
}

/**
 * A memory repository that is real except for its delete, written as a delegation so a
 * method added to the interface cannot go missing here.
 */
private class FailingDeleteMemoryRepository(
    private val delegate: MemoryRepository,
) : MemoryRepository by delegate {
    override suspend fun delete(id: String): Unit =
        throw IllegalStateException("the database refused the delete")
}

/** The same idea for the attachment delete. */
private class FailingRemoveMediaRepository(
    private val delegate: MediaRepository,
) : MediaRepository by delegate {
    override suspend fun remove(id: String): Unit =
        throw IllegalStateException("the file is in use")
}
