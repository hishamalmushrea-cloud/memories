package com.memorymap.ui.memories

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.R
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.repository.MediaRepositoryImpl
import com.memorymap.data.repository.MemoryRepositoryImpl
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.Visibility
import com.memorymap.testing.FakeAuthRepository
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
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
 * The editor is where a memory becomes real, so these cover the rules that
 * matter: a title is required, a save writes to Room, an edit reloads what is
 * stored, and removing an attachment tombstones it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryEditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MemoryMapDatabase
    private lateinit var memories: MemoryRepositoryImpl
    private lateinit var media: MediaRepositoryImpl

    private val userId = "user-1"
    private val day = LocalDate.of(2026, 9, 23)

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
    fun `saving without a title is refused and writes nothing`() = runTest {
        val viewModel = editor(memoryId = "new-1")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onTextChange("نص بلا عنوان")
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(R.string.memory_error_title, viewModel.state.value.errorRes)
        assertFalse(viewModel.state.value.isSaved)
        assertNull(memories.getById("new-1"))
    }

    @Test
    fun `saving writes the memory with the fields the user chose`() = runTest {
        val viewModel = editor(memoryId = "new-2")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onTitleChange("رحلة إلى صنعاء")
        viewModel.onTextChange("يوم طويل")
        viewModel.onDateChange(day)
        viewModel.onEmotionChange(Emotion.PRIDE)
        viewModel.onVisibilityChange(Visibility.SHARED)
        viewModel.onPlaceNameChange("صنعاء")
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value.isSaved)
        val stored = memories.getById("new-2")
        assertNotNull(stored)
        assertEquals("رحلة إلى صنعاء", stored!!.title)
        assertEquals("يوم طويل", stored.text)
        assertEquals(day, stored.memoryDate)
        assertEquals(Emotion.PRIDE, stored.emotion)
        assertEquals(Visibility.SHARED, stored.visibility)
        assertEquals("صنعاء", stored.placeName)
        assertEquals(userId, stored.userId)
    }

    @Test
    fun `a blank place name is stored as no place`() = runTest {
        val viewModel = editor(memoryId = "new-3")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.onTitleChange("بلا مكان")
        viewModel.onPlaceNameChange("   ")
        viewModel.save()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(memories.getById("new-3")!!.placeName)
    }

    @Test
    fun `editing reloads what is stored and keeps the same id`() = runTest {
        val existing = com.memorymap.domain.model.Memory(
            id = "existing-1",
            userId = userId,
            title = "ذكرى محفوظة",
            text = "تفاصيل",
            memoryDate = day,
            emotion = Emotion.LOVE,
            placeName = "عدن",
        )
        memories.save(existing)
        media.attach(
            MediaItem(
                ownerId = "existing-1",
                ownerType = MediaOwner.MEMORY,
                type = MediaType.PHOTO,
                uri = "/tmp/photo.jpg",
            ),
        )

        val viewModel = editor(memoryId = "existing-1")
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.isNew)
        assertEquals("ذكرى محفوظة", state.title)
        assertEquals("تفاصيل", state.text)
        assertEquals(day, state.date)
        assertEquals(Emotion.LOVE, state.emotion)
        assertEquals("عدن", state.placeName)
        assertEquals(1, state.savedAttachments.size)
        assertTrue(state.pendingAttachments.isEmpty())
    }

    @Test
    fun `removing a stored attachment tombstones it`() = runTest {
        memories.save(
            com.memorymap.domain.model.Memory(
                id = "existing-2",
                userId = userId,
                title = "مع مرفق",
                memoryDate = day,
            ),
        )
        val attachment = MediaItem(
            ownerId = "existing-2",
            ownerType = MediaOwner.MEMORY,
            type = MediaType.AUDIO,
            uri = "/tmp/voice.m4a",
        )
        media.attach(attachment)

        val viewModel = editor(memoryId = "existing-2")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, viewModel.state.value.savedAttachments.size)

        viewModel.removeAttachment(attachment)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.state.value.savedAttachments.isEmpty())
        assertEquals(1, db.mediaDao().tombstones().size)
    }

    @Test
    fun `a new editor starts empty and owned by nobody yet`() = runTest {
        val viewModel = editor(memoryId = null)
        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.isNew)
        assertTrue(state.title.isEmpty())
        assertEquals(Emotion.NOSTALGIA, state.emotion)
        assertEquals(Visibility.PRIVATE, state.visibility)
        assertTrue(state.attachments.isEmpty())
        // An id exists before the first save so attachments have an owner.
        assertTrue(state.memoryId.isNotBlank())
    }

    private fun editor(memoryId: String?) = MemoryEditorViewModel(
        context = ApplicationProvider.getApplicationContext(),
        savedStateHandle = SavedStateHandle(mapOf("memoryId" to memoryId.orEmpty())),
        memoryRepository = memories,
        mediaRepository = media,
        authRepository = FakeAuthRepository(userId),
    )
}
