package com.memorymap.ui.memories

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.repository.MediaRepositoryImpl
import com.memorymap.R
import com.memorymap.data.repository.MemoryRepositoryImpl
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.Memory
import com.memorymap.domain.repository.MemoryRepository
import com.memorymap.testing.FakeAuthRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ViewModel level test for the memory list: what the signed-in user saved, a
 * live filter over it, and a delete that leaves a tombstone behind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoriesViewModelTest {

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
        // The main dispatcher is put back before the database is closed, not after:
        // a view model collects and loads for as long as it lives, a test never
        // clears one, and anything the test scheduler delivers late then lands on a
        // database that is still open instead of raising "attempt to re-open an
        // already-closed object" in whichever test happens to be running when it
        // arrives.
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `the list exposes the memories of the signed-in user`() = runTest {
        memories.save(memory("رحلة إلى عدن"))
        memories.save(memory("عيد ميلاد سارة"))

        viewModel(FakeAuthRepository(userId)).state.test {
            val state = awaitWhere { !it.isLoading && it.memories.size == 2 }
            assertEquals(setOf("رحلة إلى عدن", "عيد ميلاد سارة"), state.memories.map { it.title }.toSet())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `another account never sees this user memories`() = runTest {
        memories.save(memory("ذكرى خاصة"))

        viewModel(FakeAuthRepository("someone-else")).state.test {
            val state = awaitWhere { !it.isLoading }
            assertTrue(state.memories.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the filter narrows the list by title, body and place`() = runTest {
        memories.save(memory("اجتماع العمل", text = "تحدثنا عن المشروع"))
        memories.save(memory("رحلة", text = "ذهبنا إلى البحر", placeName = "عدن"))

        val viewModel = viewModel(FakeAuthRepository(userId))
        viewModel.state.test {
            awaitWhere { !it.isLoading && it.memories.size == 2 }

            viewModel.onQueryChange("العمل")
            assertEquals("اجتماع العمل", awaitWhere { it.query == "العمل" }.memories.single().title)

            viewModel.onQueryChange("البحر")
            assertEquals("رحلة", awaitWhere { it.query == "البحر" }.memories.single().title)

            viewModel.onQueryChange("عدن")
            assertEquals("رحلة", awaitWhere { it.query == "عدن" }.memories.single().title)

            viewModel.onQueryChange("لا يوجد مثله")
            assertTrue(awaitWhere { it.query == "لا يوجد مثله" }.memories.isEmpty())

            viewModel.onQueryChange("")
            assertEquals(2, awaitWhere { it.query == "" }.memories.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a memory hides it and leaves a tombstone for the next sync`() = runTest {
        val target = memory("سأحذفها")
        memories.save(target)
        media.attach(
            MediaItem(
                ownerId = target.id,
                ownerType = MediaOwner.MEMORY,
                type = MediaType.PHOTO,
                uri = "/tmp/does-not-matter.jpg",
            ),
        )

        val viewModel = viewModel(FakeAuthRepository(userId))
        dispatcher.scheduler.advanceUntilIdle()

        // Room writes run on Room's own executor, which the test scheduler does
        // not drive, so advanceUntilIdle does not cover them. Wait until the list
        // has actually lost the memory before reading the row back, otherwise
        // this asserts against a delete that has not landed yet.
        viewModel.state.test {
            awaitWhere { !it.isLoading && it.memories.size == 1 }

            viewModel.delete(target.id)

            awaitWhere { it.memories.isEmpty() }
            cancelAndIgnoreRemainingEvents()
        }

        val stored = db.memoryDao().getById(target.id)
        assertEquals("the row must survive as a tombstone", target.id, stored?.id)
        assertEquals("PENDING_DELETE", stored?.syncStatus)
        assertTrue(memories.watchAll(userId).first().isEmpty())
        assertTrue(media.getFor(MediaOwner.MEMORY, target.id).isEmpty())
        assertEquals(1, db.mediaDao().tombstones().size)
    }

    /**
     * A delete that cannot happen has to say so.
     *
     * The failure this covers was invisible: the row stayed, nothing was said, and
     * to the person who asked for the delete it looked like the app ignoring them -
     * or worse, like the memory coming back by itself. The message is the point of
     * the banner, so the test is about the message.
     */
    @Test
    fun `a delete that fails says so instead of doing nothing`() = runTest {
        val saved = memory("لن تُحذف")
        memories.save(saved)

        val viewModel = MemoriesViewModel(
            memoryRepository = FailingDeleteMemoryRepository(memories),
            mediaRepository = media,
            authRepository = FakeAuthRepository(userId),
        )

        viewModel.state.test {
            awaitWhere { !it.isLoading }
            viewModel.delete(saved.id)

            val failed = awaitWhere { it.errorRes != null }

            assertEquals(R.string.memory_error_delete, failed.errorRes)
            // The row is still there, which is exactly why the message matters.
            assertEquals(saved.id, db.memoryDao().getById(saved.id)?.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun viewModel(auth: FakeAuthRepository) = MemoriesViewModel(
        memoryRepository = memories,
        mediaRepository = media,
        authRepository = auth,
    )

    private fun memory(title: String, text: String = "", placeName: String? = null) = Memory(
        userId = userId,
        title = title,
        text = text,
        placeName = placeName,
        memoryDate = LocalDate.of(2026, 9, 23),
    )

    /** Skips intermediate emissions until one satisfies [predicate]. */
    private suspend fun ReceiveTurbine<MemoriesUiState>.awaitWhere(
        predicate: (MemoriesUiState) -> Boolean,
    ): MemoriesUiState {
        var state = awaitItem()
        while (!predicate(state)) {
            state = awaitItem()
        }
        return state
    }
}

/**
 * Every call goes to [delegate] except the delete, which throws.
 *
 * Written as a decorator rather than a hand-written fake so it cannot drift from
 * the interface: a method added to `MemoryRepository` arrives here automatically.
 */
private class FailingDeleteMemoryRepository(
    private val delegate: MemoryRepository,
) : MemoryRepository by delegate {
    override suspend fun delete(id: String): Unit =
        throw IllegalStateException("the database refused the delete")
}
