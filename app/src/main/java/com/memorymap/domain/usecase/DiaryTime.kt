package com.memorymap.domain.usecase

import com.memorymap.domain.model.DayContentCounts
import com.memorymap.domain.model.MonthCount
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Pure calendar arithmetic behind the diary browsing model:
 *
 * ```text
 * today -> week -> month -> year
 * ```
 *
 * The diary week starts on Sunday, matching the convention used across the
 * Arabic-speaking world, regardless of the device locale.
 *
 * This object has no Android dependency on purpose: the week, month, year and
 * calendar screens are unit-tested against it directly.
 */
object DiaryTime {

    private val WEEK_FIELDS: WeekFields = WeekFields.of(DayOfWeek.SUNDAY, 1)

    /** Sunday that opens the week containing [day]. */
    fun weekStart(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    /** Saturday that closes the week containing [day]. */
    fun weekEnd(day: LocalDate): LocalDate = weekStart(day).plusDays(6)

    /** The seven days of the week containing [day], Sunday first. */
    fun weekDays(day: LocalDate): List<LocalDate> =
        (0L until 7L).map { weekStart(day).plusDays(it) }

    /**
     * 1-based index of [day] inside its month expressed in Sunday-first weeks,
     * which is how the week page is labelled ("week 4 of September").
     */
    fun weekIndexInMonth(day: LocalDate): Int {
        val firstWeekStart = weekStart(day.withDayOfMonth(1))
        val weeks = ((weekStart(day).toEpochDay() - firstWeekStart.toEpochDay()) / 7).toInt()
        return weeks + 1
    }

    /** Week number of [day] within its own year, Sunday-first. */
    fun weekOfYear(day: LocalDate): Int = day.get(WEEK_FIELDS.weekOfWeekBasedYear())

    /**
     * Splits [yearMonth] into Sunday-first weeks. Weeks are clipped to the month,
     * so the first and last week may hold fewer than seven days.
     */
    fun weeksOfMonth(yearMonth: YearMonth): List<List<LocalDate>> {
        val last = yearMonth.atEndOfMonth()
        var cursor = weekStart(yearMonth.atDay(1))
        val weeks = mutableListOf<List<LocalDate>>()
        while (!cursor.isAfter(last)) {
            weeks += weekDays(cursor).filter { YearMonth.from(it) == yearMonth }
            cursor = cursor.plusDays(7)
        }
        return weeks
    }

    /** Months of a year that already contain at least one record, ascending. */
    fun monthsWithContent(counts: List<DayContentCounts>, year: Int): List<MonthCount> =
        counts
            .filter { it.date.year == year && it.hasContent }
            .groupingBy { it.date.monthValue }
            .eachCount()
            .toSortedMap()
            .map { (month, count) -> MonthCount(year, month, count) }

    /** Days of [yearMonth] that have content, keyed by day-of-month. */
    fun daysWithContent(counts: List<DayContentCounts>, yearMonth: YearMonth): Map<Int, DayContentCounts> =
        counts
            .filter { it.hasContent && YearMonth.from(it.date) == yearMonth }
            .associateBy { it.date.dayOfMonth }

    /**
     * "On this day": every record from a previous year sharing [day]'s month and
     * day-of-month, most recent year first. This is a plain date match, nothing
     * is generated.
     */
    fun <T> onThisDay(items: List<Pair<LocalDate, T>>, day: LocalDate): List<T> =
        items
            .filter { (date, _) ->
                date.monthValue == day.monthValue &&
                    date.dayOfMonth == day.dayOfMonth &&
                    date.year != day.year
            }
            .sortedByDescending { it.first.year }
            .map { it.second }

    /** ISO-8601 (`yyyy-MM-dd`) key for a day; also the Room column format. */
    fun isoDate(day: LocalDate): String = day.toString()

    /**
     * LIKE pattern matching every ISO date with the same month and day as [day],
     * e.g. `2026-09-23` becomes `____-09-23`. Used by the "on this day" queries.
     */
    fun sameMonthDayPattern(day: LocalDate): String =
        "____-%02d-%02d".format(day.monthValue, day.dayOfMonth)

    /** Localized month names, January first. */
    fun monthNames(locale: Locale): List<String> =
        (1..12).map { month -> Month.of(month).getDisplayName(TextStyle.FULL_STANDALONE, locale) }

    /** Localized weekday names, Sunday first, to match the diary week page. */
    fun weekdayNames(locale: Locale): List<String> =
        (0L until 7L).map { offset ->
            DayOfWeek.SUNDAY.plus(offset).getDisplayName(TextStyle.FULL_STANDALONE, locale)
        }

    /** e.g. "الأربعاء 23 سبتمبر 2026" / "Wednesday, 23 September 2026". */
    fun dayHeader(day: LocalDate, locale: Locale): String {
        val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
        val month = day.month.getDisplayName(TextStyle.FULL, locale)
        return if (locale.language == "ar") {
            "$weekday ${day.dayOfMonth} $month ${day.year}"
        } else {
            "$weekday, ${day.dayOfMonth} ${month} ${day.year}"
        }
    }

    /** e.g. "الأسبوع الرابع" / "Week 4". */
    fun weekLabel(day: LocalDate, locale: Locale): String {
        val index = weekIndexInMonth(day)
        return if (locale.language == "ar") "الأسبوع $index" else "Week $index"
    }
}
