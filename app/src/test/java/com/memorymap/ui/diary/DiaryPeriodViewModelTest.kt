package com.memorymap.ui.diary

import com.memorymap.domain.model.DayContentCounts
import com.memorymap.domain.usecase.DiaryTime
import com.memorymap.testing.FakeAuthRepository
import com.memorymap.testing.RecordingDiaryRepository
import java.time.LocalDate
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The week, month and year pages, which are one aggregation with three windows.
 *
 * What is worth checking is the window: that a month asks for its own first and last day
 * and not for some neighbouring days, that asking twice for the same window does not
 * restart the collection, and that a new window replaces the old one. A wrong window here
 * is a page that looks fine and shows the wrong week - the kind of fault that survives a
 * glance at the screen.
 *
 * The window is asserted against `DiaryTime` rather than against literal dates as well as
 * with them: the literal dates prove the arithmetic, and `DiaryTime` proves the page uses
 * the one definition of "this week" that the rest of the app uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiaryPeriodViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Wednesday, 23 September 2026 - mid-week on purpose, so a wrong start shows. */
    private val day = LocalDate.of(2026, 9, 23)

    private fun viewModel(
        repository: RecordingDiaryRepository = RecordingDiaryRepository(),
        userId: String? = "user-1",
    ) = DiaryPeriodViewModel(repository, FakeAuthRepository(userId))

    @Test
    fun `with no signed-in account the page has nothing to show`() = runTest {
        val repository = RecordingDiaryRepository()
        val model = viewModel(repository, userId = null)

        model.observeWeek(day)
        advanceUntilIdle()

        assertTrue(repository.windows.isEmpty())
        assertTrue(model.state.value.isLoading)
        assertTrue(model.state.value.counts.isEmpty())
    }

    @Test
    fun `the week window is Sunday to Saturday`() = runTest {
        val repository = RecordingDiaryRepository()
        val model = viewModel(repository)

        model.observeWeek(day)
        advanceUntilIdle()

        assertEquals(
            listOf(DiaryTime.weekStart(day) to DiaryTime.weekEnd(day)),
            repository.windows,
        )
        assertEquals(
            LocalDate.of(2026, 9, 20) to LocalDate.of(2026, 9, 26),
            repository.windows.single(),
        )
    }

    @Test
    fun `the month window is its first and its last day`() = runTest {
        val repository = RecordingDiaryRepository()
        val model = viewModel(repository)

        // February in a non-leap year: the last day is the one a wrong month window
        // gets wrong, and it is the day the month grid is drawn from.
        model.observeMonth(LocalDate.of(2026, 2, 10))
        advanceUntilIdle()

        assertEquals(
            LocalDate.of(2026, 2, 1) to LocalDate.of(2026, 2, 28),
            repository.windows.single(),
        )
    }

    @Test
    fun `a leap february ends on the twenty ninth`() = runTest {
        val repository = RecordingDiaryRepository()
        val model = viewModel(repository)

        model.observeMonth(LocalDate.of(2028, 2, 15))
        advanceUntilIdle()

        assertEquals(
            LocalDate.of(2028, 2, 1) to LocalDate.of(2028, 2, 29),
            repository.windows.single(),
        )
    }

    @Test
    fun `the year window is the first to the last day of the year`() = runTest {
        val repository = RecordingDiaryRepository()
        val model = viewModel(repository)

        model.observeYear(2026)
        advanceUntilIdle()

        assertEquals(
            LocalDate.of(2026, 1, 1) to LocalDate.of(2026, 12, 31),
            repository.windows.single(),
        )
    }

    @Test
    fun `the counters of the period reach the page`() = runTest {
        val repository = RecordingDiaryRepository()
        val model = viewModel(repository)

        model.observeWeek(day)
        advanceUntilIdle()

        val counts = listOf(
            DayContentCounts(day, entries = 3, photos = 2, audio = 0, videos = 0, memories = 1, hasDiaryNote = true),
            DayContentCounts(day.plusDays(1), entries = 1, photos = 0, audio = 1, videos = 0, memories = 0, hasDiaryNote = false),
        )
        repository.emit(counts)
        advanceUntilIdle()

        assertEquals(counts, model.state.value.counts)
        assertFalse(model.state.value.isLoading)
    }

    @Test
    fun `asking for the same window twice does not restart the collection`() = runTest {
        val repository = RecordingDiaryRepository()
        val model = viewModel(repository)

        model.observe(day, day.plusDays(6))
        advanceUntilIdle()
        model.observe(day, day.plusDays(6))
        advanceUntilIdle()

        // Re-subscribing on every recomposition would restart a database query per frame.
        assertEquals(1, repository.windows.size)
    }

    @Test
    fun `a new window replaces the old one`() = runTest {
        val repository = RecordingDiaryRepository()
        val model = viewModel(repository)

        model.observeWeek(day)
        advanceUntilIdle()
        model.observeMonth(day)
        advanceUntilIdle()

        assertEquals(2, repository.windows.size)
        assertEquals(
            LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 30),
            repository.windows.last(),
        )
    }

    @Test
    fun `a failing query leaves the page loading instead of crashing`() = runTest {
        val repository = RecordingDiaryRepository()
        repository.fails = true
        val model = viewModel(repository)

        model.observeWeek(day)
        advanceUntilIdle()

        // The screen shows its loading state rather than an exception, and the failure is
        // in the log. Nothing is shown as "no content", which would be a claim the app
        // cannot support when the query never answered.
        assertTrue(model.state.value.isLoading)
        assertTrue(model.state.value.counts.isEmpty())
    }
}
