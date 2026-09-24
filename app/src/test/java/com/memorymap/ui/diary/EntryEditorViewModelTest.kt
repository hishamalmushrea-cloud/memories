package com.memorymap.ui.diary

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.memorymap.R
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.repository.DiaryRepositoryImpl
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Emotion
import com.memorymap.testing.FakeAuthRepository
import com.memorymap.testing.FakeReferenceRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * The event editor, which is what turns a day into a timeline: an event needs a
 * clock time, and editing one must reload what was stored.
 *
 * Room runs its suspend queries on a real executor that the test scheduler
 * cannot fast-forward, so every assertion waits on the state stream.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntryEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MemoryMapDatabase
    private lateinit var diary: DiaryRepositoryImpl

    private val userId = "user-1"
    private val day = LocalDate.of(2026, 9, 23)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        diary = DiaryRepositoryImpl(db.dailyEntryDao(), db.diaryNoteDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `saving without a title is refused and writes nothing`() = runTest {
        val viewModel = editor(entryId = "new-1")

        viewModel.onTextChange("نص بلا عنوان")
        viewModel.save()

        assertEquals(R.string.entry_error_title, viewModel.state.value.errorRes)
        assertFalse(viewModel.state.value.isSaved)
        assertNull(diary.getEntry("new-1"))
    }

    @Test
    fun `saving writes the event on its day at its clock time`() = runTest {
        val viewModel = editor(entryId = "new-2")

        viewModel.onTitleChange("اجتماع الصباح")
        viewModel.onTextChange("تحدثنا عن الخطة")
        viewModel.onDateChange(day)
        viewModel.onHourChange(9)
        viewModel.onMinuteChange(45)
        viewModel.onEmotionChange(Emotion.PRIDE)
        viewModel.save()

        val state = awaitState(viewModel) { it.isSaved }
        assertTrue(state.isSaved)

        val stored = diary.getEntry("new-2")
        assertNotNull(stored)
        assertEquals("اجتماع الصباح", stored!!.title)
        assertEquals("تحدثنا عن الخطة", stored.text)
        assertEquals(day, stored.date)
        assertEquals(LocalTime.of(9, 45), stored.time.toLocalTime())
        assertEquals(Emotion.PRIDE, stored.emotion)
        assertEquals(userId, stored.userId)
    }

    @Test
    fun `editing reloads the stored event including its time`() = runTest {
        diary.saveEntry(
            DailyEntry(
                id = "existing-1",
                userId = userId,
                date = day,
                time = LocalDateTime.of(day, LocalTime.of(18, 5)),
                title = "عودة إلى المنزل",
                text = "كان الطريق هادئًا",
                emotion = Emotion.SAD,
            ),
        )

        val viewModel = editor(entryId = "existing-1", date = day)
        val state = awaitState(viewModel) { !it.isNew }

        assertFalse(state.isNew)
        assertEquals("existing-1", state.entryId)
        assertEquals("عودة إلى المنزل", state.title)
        assertEquals("كان الطريق هادئًا", state.text)
        assertEquals(day, state.date)
        assertEquals(18, state.hour)
        assertEquals(5, state.minute)
        assertEquals(Emotion.SAD, state.emotion)
    }

    @Test
    fun `tapping the same emotion again clears it`() = runTest {
        val viewModel = editor(entryId = "new-3")

        viewModel.onEmotionChange(Emotion.LOVE)
        assertEquals(Emotion.LOVE, viewModel.state.value.emotion)

        viewModel.onEmotionChange(Emotion.LOVE)
        assertNull("an event need not carry an emotion", viewModel.state.value.emotion)
    }

    @Test
    fun `deleting an event leaves a tombstone for the next sync`() = runTest {
        diary.saveEntry(
            DailyEntry(
                id = "existing-2",
                userId = userId,
                date = day,
                time = LocalDateTime.of(day, LocalTime.of(8, 0)),
                title = "سأحذفه",
            ),
        )

        val viewModel = editor(entryId = "existing-2", date = day)
        awaitState(viewModel) { !it.isNew }

        viewModel.delete()
        awaitState(viewModel) { it.isSaved }

        val stored = db.dailyEntryDao().getById("existing-2")
        assertEquals("the row must survive as a tombstone", "existing-2", stored?.id)
        assertEquals("PENDING_DELETE", stored?.syncStatus)
        assertTrue(diary.watchDay(userId, day).first().isEmpty())
    }

    @Test
    fun `a new editor opens on the day it was given`() = runTest {
        val viewModel = editor(entryId = null, date = day)

        val state = viewModel.state.value
        assertTrue(state.isNew)
        assertEquals(day, state.date)
        assertTrue(state.title.isEmpty())
        assertNull(state.emotion)
        assertTrue(state.entryId.isNotBlank())
    }

    @Test
    fun `out of range clock values are clamped, not stored`() = runTest {
        val viewModel = editor(entryId = "new-4")

        viewModel.onHourChange(31)
        viewModel.onMinuteChange(-10)
        assertEquals(23, viewModel.state.value.hour)
        assertEquals(0, viewModel.state.value.minute)
        assertEquals(LocalTime.of(23, 0), viewModel.state.value.time)
    }

    private fun editor(entryId: String?, date: LocalDate = day) = EntryEditorViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf("entryId" to entryId.orEmpty(), "date" to date.toString()),
        ),
        diaryRepository = diary,
        authRepository = FakeAuthRepository(userId),
        referenceRepository = FakeReferenceRepository(),
    )

    /** Waits on the real state stream until [predicate] holds, then returns it. */
    private suspend fun awaitState(
        viewModel: EntryEditorViewModel,
        predicate: (EntryEditorUiState) -> Boolean,
    ): EntryEditorUiState {
        var result = viewModel.state.value
        viewModel.state.test {
            var state = awaitItem()
            while (!predicate(state)) {
                state = awaitItem()
            }
            result = state
            cancelAndIgnoreRemainingEvents()
        }
        return result
    }
}
