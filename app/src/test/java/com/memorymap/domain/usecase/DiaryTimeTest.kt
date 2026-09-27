package com.memorymap.domain.usecase

import com.memorymap.domain.model.DayContentCounts
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diary browsing model (day -> week -> month -> year) lives here, so these
 * tests are the guard against an off-by-one week or a clipped month grid.
 */
class DiaryTimeTest {

    private val wednesday = LocalDate.of(2026, 9, 23) // a Wednesday

    @Test
    fun `week starts on sunday and ends on saturday`() {
        assertEquals(LocalDate.of(2026, 9, 20), DiaryTime.weekStart(wednesday))
        assertEquals(LocalDate.of(2026, 9, 26), DiaryTime.weekEnd(wednesday))
    }

    @Test
    fun `week always holds seven days in order`() {
        val days = DiaryTime.weekDays(wednesday)
        assertEquals(7, days.size)
        assertEquals(LocalDate.of(2026, 9, 20), days.first())
        assertEquals(LocalDate.of(2026, 9, 26), days.last())
        assertEquals(days.sorted(), days)
    }

    @Test
    fun `a sunday belongs to the week it opens`() {
        val sunday = LocalDate.of(2026, 9, 20)
        assertEquals(sunday, DiaryTime.weekStart(sunday))
    }

    @Test
    fun `week index counts sunday-first weeks inside the month`() {
        // September 2026 starts on a Tuesday, so its first Sunday-first week
        // begins on 30 August and 23 September falls in the fourth one.
        assertEquals(4, DiaryTime.weekIndexInMonth(wednesday))
        assertEquals(1, DiaryTime.weekIndexInMonth(LocalDate.of(2026, 9, 1)))
    }

    @Test
    fun `weeks of a month cover every day exactly once`() {
        val weeks = DiaryTime.weeksOfMonth(java.time.YearMonth.of(2026, 9))
        val flattened = weeks.flatten()
        assertEquals(30, flattened.size)
        assertEquals(30, flattened.distinct().size)
        assertEquals(LocalDate.of(2026, 9, 1), flattened.min())
        assertEquals(LocalDate.of(2026, 9, 30), flattened.max())
        // First and last weeks are clipped to the month, so they can be short.
        assertTrue(weeks.all { it.size in 1..7 })
    }

    @Test
    fun `february of a leap year is fully covered`() {
        val weeks = DiaryTime.weeksOfMonth(java.time.YearMonth.of(2028, 2))
        assertEquals(29, weeks.flatten().distinct().size)
    }

    @Test
    fun `iso date uses zero padded month and day`() {
        assertEquals("2026-09-23", DiaryTime.isoDate(wednesday))
        assertEquals("2026-01-05", DiaryTime.isoDate(LocalDate.of(2026, 1, 5)))
    }

    @Test
    fun `same month day pattern matches any year`() {
        val pattern = DiaryTime.sameMonthDayPattern(wednesday)
        assertEquals("____-09-23", pattern)
        assertTrue("2025-09-23".matches(pattern.replace("____", "\\d{4}").toRegex()))
        assertFalse("2025-09-24".matches(pattern.replace("____", "\\d{4}").toRegex()))
    }

    @Test
    fun `on this day keeps only earlier years, newest first`() {
        val items = listOf(
            LocalDate.of(2023, 9, 23) to "trip",
            LocalDate.of(2025, 9, 23) to "wedding",
            LocalDate.of(2026, 9, 23) to "today, excluded",
            LocalDate.of(2024, 8, 23) to "wrong month",
        )
        assertEquals(listOf("wedding", "trip"), DiaryTime.onThisDay(items, wednesday))
    }

    @Test
    fun `months with content only lists months that have something`() {
        val counts = listOf(
            counts(LocalDate.of(2026, 9, 23), entries = 3),
            counts(LocalDate.of(2026, 9, 24), entries = 1),
            counts(LocalDate.of(2026, 12, 1), memories = 2),
            counts(LocalDate.of(2025, 5, 5), entries = 9),
        )
        val months = DiaryTime.monthsWithContent(counts, 2026)
        assertEquals(listOf(9, 12), months.map { it.month })
        assertEquals(2, months.first { it.month == 9 }.count)
    }

    @Test
    fun `days with content skips empty days`() {
        val yearMonth = java.time.YearMonth.of(2026, 9)
        val counts = listOf(
            counts(LocalDate.of(2026, 9, 23), entries = 2),
            counts(LocalDate.of(2026, 9, 24)), // empty, must be dropped
            counts(LocalDate.of(2026, 10, 1), entries = 4), // other month
        )
        val days = DiaryTime.daysWithContent(counts, yearMonth)
        assertEquals(setOf(23), days.keys)
    }

    @Test
    fun `weekday names start on sunday and there are twelve months`() {
        val arabic = DiaryTime.weekdayNames(Locale("ar"))
        assertEquals(7, arabic.size)
        assertEquals(12, DiaryTime.monthNames(Locale("ar")).size)
        assertEquals(12, DiaryTime.monthNames(Locale.ENGLISH).size)
        assertEquals("Sunday", DiaryTime.weekdayNames(Locale.ENGLISH).first())
    }

    @Test
    fun `day header follows the locale word order`() {
        val arabic = DiaryTime.dayHeader(wednesday, Locale("ar"))
        val english = DiaryTime.dayHeader(wednesday, Locale.ENGLISH)
        assertTrue(arabic.contains("23"))
        assertTrue(arabic.contains("2026"))
        assertEquals("Wednesday, 23 September 2026", english)
    }

    @Test
    fun `week label is localised`() {
        assertEquals("الأسبوع 4", DiaryTime.weekLabel(wednesday, Locale("ar")))
        assertEquals("Week 4", DiaryTime.weekLabel(wednesday, Locale.ENGLISH))
    }

    private fun counts(
        date: LocalDate,
        entries: Int = 0,
        photos: Int = 0,
        audio: Int = 0,
        videos: Int = 0,
        memories: Int = 0,
        hasDiaryNote: Boolean = false,
    ) = DayContentCounts(date, entries, photos, audio, videos, memories, hasDiaryNote)
}
