package com.memorymap.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.SyncStatus
import com.memorymap.domain.model.Visibility
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the real Room implementation of the repository against an in-memory
 * database. These are the invariants the whole offline-first design rests on:
 * a write is local and pending, and a delete leaves a tombstone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryRepositoryImplTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var repository: MemoryRepositoryImpl

    private val userId = "user-1"
    private val date = LocalDate.of(2026, 9, 23)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = MemoryRepositoryImpl(db.memoryDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a new memory is stored locally and marked pending create`() = runTest {
        val memory = sampleMemory(title = "رحلة إلى إب")

        repository.save(memory)

        val stored = repository.getById(memory.id)
        assertNotNull(stored)
        assertEquals(SyncStatus.PENDING_CREATE, stored!!.syncStatus)
        assertEquals(listOf(memory.id), repository.watchAll(userId).first().map { it.id })
    }

    @Test
    fun `a location survives the entity round trip`() = runTest {
        val memory = sampleMemory(title = "صنعاء القديمة", location = GeoPoint(15.3694, 44.1910))

        repository.save(memory)

        val stored = repository.getById(memory.id)
        assertEquals(GeoPoint(15.3694, 44.1910), stored?.location)
        assertEquals(1, repository.watchLocated(userId).first().size)
    }

    @Test
    fun `editing an existing memory switches it to pending update and keeps the creation time`() = runTest {
        val memory = sampleMemory(title = "قبل التعديل")
        repository.save(memory)
        val createdAt = repository.getById(memory.id)!!.createdAt

        repository.save(memory.copy(title = "بعد التعديل"))

        val stored = repository.getById(memory.id)!!
        assertEquals("بعد التعديل", stored.title)
        assertEquals(SyncStatus.PENDING_UPDATE, stored.syncStatus)
        assertEquals(createdAt, stored.createdAt)
    }

    @Test
    fun `deleting keeps a tombstone so the server delete can be replayed`() = runTest {
        val memory = sampleMemory(title = "سأحذفها")
        repository.save(memory)

        repository.delete(memory.id)

        val stored = repository.getById(memory.id)
        assertNotNull("the row must survive a local delete", stored)
        assertTrue(stored!!.isDeleted)
        assertEquals(SyncStatus.PENDING_DELETE, stored.syncStatus)
        assertTrue("deleted memories leave the visible list", repository.watchAll(userId).first().isEmpty())
        assertEquals(listOf(memory.id), db.memoryDao().pendingSync().map { it.id })
    }

    @Test
    fun `search matches title, body and place but not other records`() = runTest {
        repository.save(sampleMemory(title = "اجتماع العمل", text = "تحدثنا عن المشروع"))
        repository.save(sampleMemory(title = "رحلة", text = "ذهبنا إلى البحر", placeName = "عدن"))

        assertEquals(1, repository.search(userId, "العمل").size)
        assertEquals(1, repository.search(userId, "المشروع").size)
        assertEquals(1, repository.search(userId, "عدن").size)
        assertTrue(repository.search(userId, "غير موجود").isEmpty())
        assertTrue("an empty query returns nothing instead of everything", repository.search(userId, "  ").isEmpty())
    }

    @Test
    fun `linked people and places are replaced, not accumulated`() = runTest {
        val memory = sampleMemory(title = "مع الأصدقاء")
        repository.save(memory, personIds = listOf("p1", "p2"), placeIds = listOf("pl1"))
        assertEquals(2, db.memoryDao().peopleOf(memory.id).size)

        repository.save(memory.copy(title = memory.title), personIds = listOf("p3"), placeIds = emptyList())

        assertEquals(listOf("p3"), db.memoryDao().peopleOf(memory.id))
        assertTrue(db.memoryDao().placesOf(memory.id).isEmpty())
    }

    @Test
    fun `count ignores deleted memories`() = runTest {
        val first = sampleMemory(title = "واحدة")
        val second = sampleMemory(title = "اثنتان")
        repository.save(first)
        repository.save(second)
        assertEquals(2, repository.count(userId))

        repository.delete(first.id)

        assertEquals(1, repository.count(userId))
    }

    private fun sampleMemory(
        title: String,
        text: String = "",
        placeName: String? = null,
        location: GeoPoint? = null,
    ) = Memory(
        userId = userId,
        title = title,
        text = text,
        placeName = placeName,
        location = location,
        memoryDate = date,
        emotion = Emotion.NOSTALGIA,
        visibility = Visibility.PRIVATE,
    )
}
