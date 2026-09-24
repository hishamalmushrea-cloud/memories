package com.memorymap.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.entities.PersonEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.Visibility
import java.time.LocalDate
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Linking a record to the people and places it mentions.
 *
 * This is what makes `كل الأحداث مع أحمد` a query the database can answer rather
 * than a guess at a text match, so the links have to survive a save and, just as
 * importantly, be *replaced* by the next one. An edit that unlinks a name must
 * not leave the old link behind, or the person would keep a record they are no
 * longer part of.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordLinkingTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var memories: MemoryRepositoryImpl
    private lateinit var diary: DiaryRepositoryImpl

    private val userId = "user-1"
    private val date = LocalDate.of(2026, 9, 23)

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        memories = MemoryRepositoryImpl(db.memoryDao())
        diary = DiaryRepositoryImpl(db.dailyEntryDao(), db.diaryNoteDao())

        db.personDao().upsert(PersonEntity(id = "p1", userId = userId, name = "أحمد", createdAt = stamp))
        db.personDao().upsert(PersonEntity(id = "p2", userId = userId, name = "سعاد", createdAt = stamp))
        db.placeDao().upsert(
            PlaceEntity(id = "pl1", userId = userId, name = "إب", latitude = 13.97, longitude = 44.17, createdAt = stamp),
        )
        db.placeDao().upsert(
            PlaceEntity(id = "pl2", userId = userId, name = "صنعاء", latitude = 15.36, longitude = 44.19, createdAt = stamp),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a memory keeps the people and places it was saved with`() = runTest {
        val memory = memory("رحلة إلى إب")

        memories.save(memory, personIds = listOf("p1", "p2"), placeIds = listOf("pl1"))

        assertEquals(setOf("p1", "p2"), memories.peopleOf(memory.id).toSet())
        assertEquals(setOf("pl1"), memories.placesOf(memory.id).toSet())
    }

    @Test
    fun `saving a memory with no links leaves none behind`() = runTest {
        val memory = memory("يوم عادي")

        memories.save(memory)

        assertTrue(memories.peopleOf(memory.id).isEmpty())
        assertTrue(memories.placesOf(memory.id).isEmpty())
    }

    @Test
    fun `editing a memory replaces its links instead of adding to them`() = runTest {
        val memory = memory("رحلة إلى إب")
        memories.save(memory, personIds = listOf("p1", "p2"), placeIds = listOf("pl1", "pl2"))

        // The edit drops أحمد and صنعاء and keeps the rest.
        memories.save(memory, personIds = listOf("p2"), placeIds = listOf("pl1"))

        assertEquals(setOf("p2"), memories.peopleOf(memory.id).toSet())
        assertEquals(setOf("pl1"), memories.placesOf(memory.id).toSet())
    }

    @Test
    fun `editing a memory down to no links unlinks everyone`() = runTest {
        val memory = memory("رحلة إلى إب")
        memories.save(memory, personIds = listOf("p1"), placeIds = listOf("pl1"))

        memories.save(memory)

        assertTrue(memories.peopleOf(memory.id).isEmpty())
        assertTrue(memories.placesOf(memory.id).isEmpty())
    }

    @Test
    fun `an event keeps the people and places it was saved with`() = runTest {
        val entry = entry("زيارة أحمد")

        diary.saveEntry(entry, personIds = listOf("p1"), placeIds = listOf("pl2"))

        assertEquals(setOf("p1"), diary.peopleOf(entry.id).toSet())
        assertEquals(setOf("pl2"), diary.placesOf(entry.id).toSet())
    }

    @Test
    fun `editing an event replaces its links instead of adding to them`() = runTest {
        val entry = entry("زيارة أحمد")
        diary.saveEntry(entry, personIds = listOf("p1", "p2"), placeIds = listOf("pl1"))

        diary.saveEntry(entry, personIds = listOf("p2"), placeIds = emptyList())

        assertEquals(setOf("p2"), diary.peopleOf(entry.id).toSet())
        assertTrue(diary.placesOf(entry.id).isEmpty())
    }

    @Test
    fun `one person can be linked to both a memory and an event`() = runTest {
        val memory = memory("رحلة إلى إب")
        val entry = entry("زيارة أحمد")

        memories.save(memory, personIds = listOf("p1"))
        diary.saveEntry(entry, personIds = listOf("p1"))

        assertEquals(setOf("p1"), memories.peopleOf(memory.id).toSet())
        assertEquals(setOf("p1"), diary.peopleOf(entry.id).toSet())
    }

    private fun memory(title: String) = Memory(
        userId = userId,
        title = title,
        memoryDate = date,
        emotion = Emotion.NOSTALGIA,
        visibility = Visibility.PRIVATE,
    )

    private fun entry(title: String) = DailyEntry(
        userId = userId,
        date = date,
        time = LocalDateTime.of(date, 10, 0),
        title = title,
    )

    private companion object {
        const val stamp = "2024-01-01T00:00:00"
    }
}
