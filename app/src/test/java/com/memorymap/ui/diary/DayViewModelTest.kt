package com.memorymap.ui.diary

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.repository.DiaryRepositoryImpl
import com.memorymap.data.repository.OnThisDayRepositoryImpl
import com.memorymap.data.repository.UserRepositoryImpl
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Emotion
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ViewModel level test: the day screen must show the events of its own day and
 * must persist the end-of-day note the user typed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DayViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MemoryMapDatabase
    private lateinit var diaryRepository: DiaryRepositoryImpl

    private val day = LocalDate.of(2026, 9, 23)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        diaryRepository = DiaryRepositoryImpl(db.dailyEntryDao(), db.diaryNoteDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `the day state exposes that day events and the saved note`() = runTest {
        diaryRepository.saveEntry(entry("استيقظت", 8, 0))
        diaryRepository.saveEntry(entry("عدت إلى المنزل", 16, 40))
        diaryRepository.saveEntry(entry("حدث الأمس", 9, 0, on = day.minusDays(1)))

        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.state.test {
            var state = awaitItem()
            while (state.isLoading || state.entries.size < 2) {
                state = awaitItem()
            }
            assertEquals(day, state.date)
            assertEquals(listOf("استيقظت", "عدت إلى المنزل"), state.entries.map { it.title })

            viewModel.saveDiaryNote("خرجت مساءً مع الأصدقاء")
            dispatcher.scheduler.advanceUntilIdle()
            assertEquals("خرجت مساءً مع الأصدقاء", awaitItem().diaryNote)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals("خرجت مساءً مع الأصدقاء", diaryRepository.getDiaryNote("local", day))
    }

    @Test
    fun `deleting an event removes it from the day but keeps the tombstone`() = runTest {
        val event = entry("سأحذفه", 20, 0)
        diaryRepository.saveEntry(event)

        val viewModel = viewModel()
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.deleteEntry(event.id)
        dispatcher.scheduler.advanceUntilIdle()

        val stored = db.dailyEntryDao().getById(event.id)
        assertEquals("the row must stay until the server confirms the delete", event.id, stored?.id)
        assertEquals("PENDING_DELETE", stored?.syncStatus)
        assertEquals(0, diaryRepository.watchDay("local", day).first().size)
    }

    private fun viewModel(): DayViewModel = DayViewModel(
        savedStateHandle = SavedStateHandle(mapOf("date" to day.toString())),
        diaryRepository = diaryRepository,
        onThisDayRepository = OnThisDayRepositoryImpl(db.memoryDao(), db.diaryNoteDao()),
        userRepository = UserRepositoryImpl(db.userDao()),
    )

    private fun entry(title: String, hour: Int, minute: Int, on: LocalDate = day) = DailyEntry(
        userId = "local",
        date = on,
        time = LocalDateTime.of(on, java.time.LocalTime.of(hour, minute)),
        title = title,
        emotion = Emotion.HAPPY,
    )
}
