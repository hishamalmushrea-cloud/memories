package com.memorymap.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.Place
import com.memorymap.domain.model.SearchFilter
import com.memorymap.domain.usecase.SearchQueryParser
import java.time.LocalDate
import java.time.LocalDateTime
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
 * Search over a real Room database.
 *
 * The cases that matter are the ones where a naive `LIKE` would be wrong: words
 * that have to all be present, a name that has to narrow rather than merely
 * match, a month that means every year, and another account's rows that must
 * never appear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchRepositoryImplTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var memories: MemoryRepositoryImpl
    private lateinit var diary: DiaryRepositoryImpl
    private lateinit var references: ReferenceRepositoryImpl
    private lateinit var search: SearchRepositoryImpl

    private val userId = "user-1"
    private val intruder = "user-2"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        memories = MemoryRepositoryImpl(db.memoryDao())
        diary = DiaryRepositoryImpl(db.dailyEntryDao(), db.diaryNoteDao())
        references = ReferenceRepositoryImpl(
            db.personDao(),
            db.placeDao(),
            db.memoryDao(),
            db.dailyEntryDao(),
        )
        search = SearchRepositoryImpl(
            db.memoryDao(),
            db.dailyEntryDao(),
            db.personDao(),
            db.placeDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `an empty query returns nothing rather than the whole archive`() = runTest {
        seed()

        val results = search.search(userId, SearchQueryParser.parse("   "))

        assertTrue(results.isEmpty)
    }

    @Test
    fun `a word finds a memory by its title`() = runTest {
        seed()

        val results = search.search(userId, SearchQueryParser.parse("رحلة"))

        assertEquals(listOf("رحلة إلى عدن"), results.memories.map { it.title })
        assertTrue(results.entries.isEmpty())
    }

    @Test
    fun `a word finds a memory by its body`() = runTest {
        seed()

        val results = search.search(userId, SearchQueryParser.parse("المشروع"))

        assertEquals(listOf("اجتماع العمل"), results.memories.map { it.title })
    }

    @Test
    fun `a word finds an event by its title`() = runTest {
        seed()

        val results = search.search(userId, SearchQueryParser.parse("استيقظت"))

        assertTrue(results.memories.isEmpty())
        assertEquals(listOf("استيقظت مبكرًا"), results.entries.map { it.title })
    }

    @Test
    fun `every word has to be present, so a longer query is narrower`() = runTest {
        seed()

        val both = search.search(userId, SearchQueryParser.parse("اجتماع المشروع"))
        val neither = search.search(userId, SearchQueryParser.parse("اجتماع رحلة"))

        assertEquals(1, both.memories.size)
        assertEquals(0, neither.recordCount)
    }

    @Test
    fun `naming a person narrows to the records linked to them`() = runTest {
        seed()

        val results = search.search(userId, SearchQueryParser.parse("مع سالم"))

        // Both the memory and the event are linked to him; the unlinked ones are
        // left out even though they belong to the same account.
        assertEquals(listOf("رحلة إلى عدن"), results.memories.map { it.title })
        assertEquals(listOf("استيقظت مبكرًا"), results.entries.map { it.title })
    }

    @Test
    fun `naming a place narrows to what happened there`() = runTest {
        seed()

        val results = search.search(userId, SearchQueryParser.parse("في عدن"))

        assertEquals(listOf("رحلة إلى عدن"), results.memories.map { it.title })
        assertTrue(results.entries.isEmpty())
    }

    @Test
    fun `a month with no year matches every year that has it`() = runTest {
        seed()

        val results = search.search(userId, SearchQueryParser.parse("سبتمبر"))

        // September 2024 and September 2020; the March memory is left out.
        assertEquals(
            setOf("اجتماع العمل", "ذكرى قديمة"),
            results.memories.map { it.title }.toSet(),
        )
    }

    @Test
    fun `a year narrows to that year`() = runTest {
        seed()

        val results = search.search(userId, SearchQueryParser.parse("2020"))

        assertEquals(listOf("ذكرى قديمة"), results.memories.map { it.title })
    }

    @Test
    fun `a full date finds one day`() = runTest {
        seed()

        val results = search.search(userId, SearchQueryParser.parse("15 مارس 2019"))

        assertEquals(listOf("رحلة إلى عدن"), results.memories.map { it.title })
    }

    @Test
    fun `the emotion filter narrows on top of the words`() = runTest {
        seed()
        val query = SearchQueryParser.parse("اجتماع")

        // The meeting is filed under nostalgia, so that emotion keeps it and any
        // other drops it.
        val matching = search.search(userId, query, SearchFilter(emotion = Emotion.NOSTALGIA))
        val other = search.search(userId, query, SearchFilter(emotion = Emotion.HAPPY))

        assertEquals(1, matching.memories.size)
        assertTrue(other.memories.isEmpty())
    }

    @Test
    fun `a deleted record is never returned`() = runTest {
        seed()
        val target = search.search(userId, SearchQueryParser.parse("قديمة")).memories.single()

        memories.delete(target.id)

        val results = search.search(userId, SearchQueryParser.parse("قديمة"))

        assertTrue(results.memories.isEmpty())
    }

    @Test
    fun `another account's records never appear`() = runTest {
        seed()
        memories.save(
            Memory(
                userId = intruder,
                title = "رحلة إلى عدن",
                memoryDate = LocalDate.of(2021, 5, 5),
            ),
        )

        val results = search.search(userId, SearchQueryParser.parse("رحلة"))

        assertEquals(1, results.memories.size)
    }

    @Test
    fun `a bare name is offered as a person, not silently turned into a link`() = runTest {
        seed()

        val results = search.search(userId, SearchQueryParser.parse("سالم"))

        // The name is found, and shown as a way in. Pulling in everything linked
        // to him would conflate "the text says سالم" with "this record is about
        // سالم"; asking `مع سالم` is how you mean the second one.
        assertEquals(listOf("سالم"), results.people.map { it.name })
        assertEquals(0, results.recordCount)
    }

    @Test
    fun `gibberish finds nothing`() = runTest {
        seed()

        assertTrue(search.search(userId, SearchQueryParser.parse("زززز")).isEmpty)
    }

    @Test
    fun `with no signed-in user there is nothing to search`() = runTest {
        seed()

        assertTrue(search.search("nobody", SearchQueryParser.parse("رحلة")).isEmpty)
    }

    private suspend fun seed() {
        val salem = references.findOrCreatePerson(userId, "سالم")
        val aden = Place(
            userId = userId,
            name = "عدن",
            location = GeoPoint(12.78, 45.02),
        )
        references.savePlace(aden)

        memories.save(
            Memory(
                userId = userId,
                title = "رحلة إلى عدن",
                memoryDate = LocalDate.of(2019, 3, 15),
                emotion = Emotion.HAPPY,
            ),
            personIds = listOf(salem.id),
            placeIds = listOf(aden.id),
        )
        memories.save(
            Memory(
                userId = userId,
                title = "اجتماع العمل",
                text = "ناقشنا المشروع الجديد",
                memoryDate = LocalDate.of(2024, 9, 10),
                emotion = Emotion.NOSTALGIA,
            ),
        )
        memories.save(
            Memory(
                userId = userId,
                title = "ذكرى قديمة",
                memoryDate = LocalDate.of(2020, 9, 5),
                emotion = Emotion.NOSTALGIA,
            ),
        )

        diary.saveEntry(
            DailyEntry(
                userId = userId,
                date = LocalDate.of(2024, 9, 10),
                time = LocalDateTime.of(2024, 9, 10, 7, 0),
                title = "استيقظت مبكرًا",
            ),
            personIds = listOf(salem.id),
        )
        diary.saveEntry(
            DailyEntry(
                userId = userId,
                date = LocalDate.of(2025, 1, 2),
                time = LocalDateTime.of(2025, 1, 2, 20, 0),
                title = "يوم عادي",
            ),
        )
    }
}
