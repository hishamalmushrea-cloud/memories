package com.memorymap.domain.usecase

import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.Memory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline ordering rules.
 *
 * These are pure, so they run on the JVM with no database and no Android. The
 * rule that matters: a day reads morning to evening, and the day's memories come
 * after its events.
 */
class TimelineBuilderTest {

    private val day = LocalDate.of(2026, 9, 23)
    private val userId = "user-1"

    @Test
    fun `newest day comes first`() {
        val timeline = TimelineBuilder.build(
            events = listOf(
                event("الأقدم", day.minusDays(2), 9, 0),
                event("الأحدث", day, 9, 0),
                event("الوسط", day.minusDays(1), 9, 0),
            ),
            memories = emptyList(),
        )

        assertEquals(
            listOf(day, day.minusDays(1), day.minusDays(2)),
            timeline.map { it.date },
        )
    }

    @Test
    fun `within a day events are ordered by clock time`() {
        val timeline = TimelineBuilder.build(
            events = listOf(
                event("مساءً", day, 20, 30),
                event("فجرًا", day, 5, 15),
                event("ظهرًا", day, 12, 0),
            ),
            memories = emptyList(),
        )

        assertEquals(
            listOf("فجرًا", "ظهرًا", "مساءً"),
            timeline.single().rows.map { it.title },
        )
        assertEquals(
            listOf(LocalTime.of(5, 15), LocalTime.of(12, 0), LocalTime.of(20, 30)),
            timeline.single().rows.map { it.time },
        )
    }

    @Test
    fun `a day memories follow its events because they have no clock time`() {
        val timeline = TimelineBuilder.build(
            events = listOf(event("استيقظت", day, 7, 0)),
            memories = listOf(memory("ذكرى اليوم", day)),
        )

        val rows = timeline.single().rows
        assertEquals(listOf(false, true), rows.map { it.isMemory })
        assertEquals("استيقظت", rows.first().title)
        assertEquals(null, rows.last().time)
    }

    @Test
    fun `events and memories land on their own days`() {
        val timeline = TimelineBuilder.build(
            events = listOf(event("حدث اليوم", day, 8, 0)),
            memories = listOf(memory("ذكرى أقدم", day.minusDays(3))),
        )

        assertEquals(2, timeline.size)
        assertEquals(day, timeline[0].date)
        assertEquals("حدث اليوم", timeline[0].rows.single().title)
        assertEquals(day.minusDays(3), timeline[1].date)
        assertEquals("ذكرى أقدم", timeline[1].rows.single().title)
    }

    @Test
    fun `deleted records never reach the timeline`() {
        val timeline = TimelineBuilder.build(
            events = listOf(
                event("باقٍ", day, 8, 0),
                event("محذوف", day, 9, 0).copy(deletedAt = LocalDateTime.now()),
            ),
            memories = listOf(memory("ذكرى محذوفة", day).copy(deletedAt = LocalDateTime.now())),
        )

        assertEquals(listOf("باقٍ"), timeline.single().rows.map { it.title })
    }

    @Test
    fun `an empty archive is an empty timeline, not a crash`() {
        assertTrue(TimelineBuilder.build(emptyList(), emptyList()).isEmpty())
    }

    @Test
    fun `the emotion of a record survives into its row`() {
        val timeline = TimelineBuilder.build(
            events = listOf(event("فرح", day, 8, 0).copy(emotion = Emotion.HAPPY)),
            memories = listOf(memory("حنين", day).copy(emotion = Emotion.NOSTALGIA)),
        )

        assertEquals(
            listOf(Emotion.HAPPY, Emotion.NOSTALGIA),
            timeline.single().rows.map { it.emotion },
        )
    }

    @Test
    fun `the window start counts back inclusively from today`() {
        assertEquals(day, TimelineBuilder.windowStart(day, 1))
        assertEquals(day.minusDays(59), TimelineBuilder.windowStart(day, 60))
        // A zero or negative window must not reach into the future.
        assertEquals(day, TimelineBuilder.windowStart(day, 0))
        assertEquals(day, TimelineBuilder.windowStart(day, -5))
    }

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
}
