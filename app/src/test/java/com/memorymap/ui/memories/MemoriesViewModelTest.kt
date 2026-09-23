package com.memorymap.ui.memories

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.repository.MediaRepositoryImpl
import com.memorymap.data.repository.MemoryRepositoryImpl
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.Memory
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
        db.close()
        Dispatchers.resetMain()
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

        viewModel.delete(target.id)
        dispatcher.scheduler.advanceUntilIdle()

        val stored = db.memoryDao().getById(target.id)
        assertEquals("the row must survive as a tombstone", target.id, stored?.id)
        assertEquals("PENDING_DELETE", stored?.syncStatus)
        assertTrue(memories.watchAll(userId).first().isEmpty())
        assertTrue(media.getFor(MediaOwner.MEMORY, target.id).isEmpty())
        assertEquals(1, db.mediaDao().tombstones().size)
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
