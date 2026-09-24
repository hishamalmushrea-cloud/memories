package com.memorymap.ui.timeline

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.repository.MemoryRepositoryImpl
import com.memorymap.data.repository.DiaryRepositoryImpl
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Memory
import com.memorymap.testing.FakeAuthRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The timeline tab: one stream for events and memories, scoped to the signed-in
 * account, reading a bounded window that grows on request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimelineViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MemoryMapDatabase
    private lateinit var diary: DiaryRepositoryImpl
    private lateinit var memories: MemoryRepositoryImpl

    private val userId = "user-1"
    private val today = LocalDate.now()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        diary = DiaryRepositoryImpl(db.dailyEntryDao(), db.diaryNoteDao())
        memories = MemoryRepositoryImpl(db.memoryDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `events and memories appear in one newest-first stream`() = runTest {
        diary.saveEntry(event("حدث اليوم", today, 9, 0))
        memories.save(memory("ذكرى أقدم", today.minusDays(5)))

        viewModel(FakeAuthRepository(userId)).state.test {
            val state = awaitWhere { !it.isLoading && it.rowCount == 2 }
            assertEquals(2, state.days.size)
            assertEquals(today, state.days[0].date)
            assertEquals(today.minusDays(5), state.days[1].date)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `another account never sees this user timeline`() = runTest {
        diary.saveEntry(event("حدث خاص", today, 9, 0))

        viewModel(FakeAuthRepository("someone-else")).state.test {
            val state = awaitWhere { !it.isLoading }
            assertTrue(state.days.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `records outside the window are not read until it grows`() = runTest {
        val farPast = today.minusDays(400)
        diary.saveEntry(event("حدث قديم جدًا", farPast, 9, 0))
        diary.saveEntry(event("حدث حديث", today, 9, 0))

        val viewModel = viewModel(FakeAuthRepository(userId))
        viewModel.state.test {
            val initial = awaitWhere { !it.isLoading && it.rowCount == 1 }
            assertEquals(
                "a 400-day-old event is outside the 60-day window",
                TimelineViewModel.INITIAL_WINDOW_DAYS,
                initial.windowDays,
            )
            assertEquals(listOf("حدث حديث"), initial.days.single().rows.map { it.title })

            // Grow the window until the old day is inside it.
            repeat(7) { viewModel.loadEarlier() }
            val grown = awaitWhere { it.rowCount == 2 }
            assertTrue(grown.windowDays > TimelineViewModel.INITIAL_WINDOW_DAYS)
            assertEquals(2, grown.days.size)
            assertEquals(farPast, grown.days.last().date)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the window stops growing at its limit`() = runTest {
        val viewModel = viewModel(FakeAuthRepository(userId))
        repeat(200) { viewModel.loadEarlier() }
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            val state = awaitWhere { !it.isLoading }
            assertEquals(TimelineViewModel.MAX_WINDOW_DAYS, state.windowDays)
            assertFalse(state.canLoadEarlier)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun viewModel(auth: FakeAuthRepository) = TimelineViewModel(
        diaryRepository = diary,
        memoryRepository = memories,
        authRepository = auth,
    )

    private fun event(title: String, on: LocalDate, hour: Int, minute: Int) = DailyEntry(
        userId = userId,
        date = on,
        time = LocalDateTime.of(on, LocalTime.of(hour, minute)),
        title = title,
    )

    private fun memory(title: String, on: LocalDate) = Memory(
        userId = userId,
        title = title,
        memoryDate = on,
    )

    private suspend fun ReceiveTurbine<TimelineUiState>.awaitWhere(
        predicate: (TimelineUiState) -> Boolean,
    ): TimelineUiState {
        var state = awaitItem()
        while (!predicate(state)) {
            state = awaitItem()
        }
        return state
    }
}
