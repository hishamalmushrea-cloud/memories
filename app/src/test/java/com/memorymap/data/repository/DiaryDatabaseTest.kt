package com.memorymap.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.local.Mappers
import com.memorymap.data.local.entities.MediaEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.data.local.entities.PlaceEntity
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.SyncStatus
import com.memorymap.domain.model.Visibility
import com.memorymap.domain.usecase.DiaryTime
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.flow.first
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
 * Exercises the two queries the diary depends on most: the per-day aggregation
 * behind week/month/year/calendar, and the "on this day" date pattern.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiaryDatabaseTest {

    private lateinit var db: MemoryMapDatabase
    private lateinit var diaryRepository: DiaryRepositoryImpl
    private lateinit var onThisDay: OnThisDayRepositoryImpl

    private val userId = "user-1"
    private val day = LocalDate.of(2026, 9, 23)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        diaryRepository = DiaryRepositoryImpl(db.dailyEntryDao(), db.diaryNoteDao())
        onThisDay = OnThisDayRepositoryImpl(db.memoryDao(), db.diaryNoteDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a day reads back as an ordered log`() = runTest {
        diaryRepository.saveEntry(entry("استيقظت", at = 8, 0))
        diaryRepository.saveEntry(entry("ذهبت إلى العمل", at = 9, 15))
        diaryRepository.saveEntry(entry("كتبت نهاية اليوم", at = 23, 0))

        val events = diaryRepository.watchDay(userId, day).first()

        assertEquals(3, events.size)
        assertEquals(listOf("استيقظت", "ذهبت إلى العمل", "كتبت نهاية اليوم"), events.map { it.title })
    }

    @Test
    fun `events of another day do not leak into this day`() = runTest {
        diaryRepository.saveEntry(entry("اليوم", at = 10, 0))
        diaryRepository.saveEntry(entry("الأمس", at = 10, 0, on = day.minusDays(1)))

        assertEquals(1, diaryRepository.watchDay(userId, day).first().size)
    }

    @Test
    fun `day counts add up events, media, memories and the diary note`() = runTest {
        val first = entry("حدث أول", at = 8, 0)
        val second = entry("حدث ثان", at = 13, 30)
        diaryRepository.saveEntry(first)
        diaryRepository.saveEntry(second)

        db.mediaDao().upsertAll(
            listOf(
                media(first.id, "PHOTO", "a.jpg"),
                media(first.id, "PHOTO", "b.jpg"),
                media(second.id, "AUDIO", "c.m4a"),
                media(second.id, "VIDEO", "d.mp4"),
            ),
        )
        db.memoryDao().upsert(memory("ذكرى اليوم"))
        diaryRepository.saveDiaryNote(userId, day, "كان يومًا طويلًا")

        val counts = diaryRepository.watchDayCounts(userId, day, day).first().single()

        assertEquals(day, counts.date)
        assertEquals(2, counts.entries)
        assertEquals(2, counts.photos)
        assertEquals(1, counts.audio)
        assertEquals(1, counts.videos)
        assertEquals(1, counts.memories)
        assertTrue(counts.hasDiaryNote)
        assertTrue(counts.hasContent)
    }

    @Test
    fun `a deleted event no longer counts towards its day`() = runTest {
        val kept = entry("باق", at = 9, 0)
        val removed = entry("محذوف", at = 18, 0)
        diaryRepository.saveEntry(kept)
        diaryRepository.saveEntry(removed)

        diaryRepository.deleteEntry(removed.id)

        val counts = diaryRepository.watchDayCounts(userId, day, day).first().single()
        assertEquals(1, counts.entries)
    }

    @Test
    fun `an empty day produces no aggregation row`() = runTest {
        val counts = diaryRepository.watchDayCounts(userId, day, day.plusDays(3)).first()
        assertTrue(counts.isEmpty())
    }

    @Test
    fun `on this day returns earlier years only, newest first`() = runTest {
        db.memoryDao().upsert(memory("زواج", on = LocalDate.of(2025, 9, 23)))
        db.memoryDao().upsert(memory("رحلة", on = LocalDate.of(2023, 9, 23)))
        db.memoryDao().upsert(memory("اليوم نفسه هذه السنة", on = day))
        db.memoryDao().upsert(memory("شهر آخر", on = LocalDate.of(2024, 8, 23)))
        db.diaryNoteDao().upsert(Mappers.diaryNote(userId, LocalDate.of(2024, 9, 23), "مذكرات قديمة"))

        val items = onThisDay.items(userId, day)

        assertEquals(listOf(2025, 2024, 2023), items.map { it.year })
        assertEquals(listOf("زواج", "مذكرات قديمة", "رحلة"), items.map { it.title })
        assertTrue(items[0].isMemory)
        assertTrue("the diary note is flagged as a note, not a memory", !items[1].isMemory)
    }

    @Test
    fun `the same month day pattern pins month and day but not the year`() {
        assertEquals("____-09-23", DiaryTime.sameMonthDayPattern(day))
        assertEquals("____-01-05", DiaryTime.sameMonthDayPattern(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `a diary note can be read back and overwritten`() = runTest {
        diaryRepository.saveDiaryNote(userId, day, "النسخة الأولى")
        assertEquals("النسخة الأولى", diaryRepository.getDiaryNote(userId, day))

        diaryRepository.saveDiaryNote(userId, day, "النسخة الثانية")
        assertEquals("النسخة الثانية", diaryRepository.getDiaryNote(userId, day))
    }

    @Test
    fun `a place linked to an event survives and can be queried back`() = runTest {
        val place = PlaceEntity(
            id = "place-1",
            userId = userId,
            name = "حديقة صنعاء",
            latitude = 15.35,
            longitude = 44.20,
            createdAt = LocalDateTime.now().toString(),
        )
        db.placeDao().upsert(place)
        val event = entry("مشي في الحديقة", at = 17, 0).copy(location = GeoPoint(15.35, 44.20))

        diaryRepository.saveEntry(event, placeIds = listOf(place.id))

        val found = db.dailyEntryDao().entriesAtPlace(place.id)
        assertEquals(listOf(event.id), found.map { it.id })
    }

    // --- builders ---

    private fun entry(title: String, at: Int, minute: Int, on: LocalDate = day) = DailyEntry(
        userId = userId,
        date = on,
        time = on.atTime(at, minute),
        title = title,
        emotion = Emotion.HAPPY,
    )

    private fun media(ownerId: String, type: String, fileName: String) = MediaEntity(
        id = UUID.randomUUID().toString(),
        ownerType = MediaOwner.DAILY_ENTRY.name,
        ownerId = ownerId,
        mediaType = type,
        uri = "file:///memorymap/$fileName",
        createdAt = LocalDateTime.now().toString(),
        syncStatus = SyncStatus.PENDING_CREATE.name,
    )

    private fun memory(title: String, on: LocalDate = day) = MemoryEntity(
        id = UUID.randomUUID().toString(),
        userId = userId,
        title = title,
        text = "",
        memoryDate = on.toString(),
        emotion = Emotion.NOSTALGIA.name,
        visibility = Visibility.PRIVATE.name,
        createdAt = LocalDateTime.now().toString(),
        updatedAt = LocalDateTime.now().toString(),
        syncStatus = SyncStatus.PENDING_CREATE.name,
    )
}
