package com.memorymap.domain.usecase

import java.time.LocalDate

/**
 * The dates a result is allowed to have.
 *
 * A plain range cannot express what the search box asks for: `مذكرات سبتمبر`
 * means September of *any* year, which is a shape, not an interval. So the
 * constraint carries both, and a date has to satisfy every part that is set.
 *
 * Pure, so the matching rules are tested on the JVM.
 */
data class DateConstraint(
    val year: Int? = null,
    val month: Int? = null,
    val day: Int? = null,
) {

    /** True when nothing was asked about the date at all. */
    val isUnconstrained: Boolean get() = year == null && month == null && day == null

    fun matches(date: LocalDate): Boolean {
        if (year != null && date.year != year) return false
        if (month != null && date.monthValue != month) return false
        if (day != null && date.dayOfMonth != day) return false
        return true
    }

    companion object {

        /** The date the typed query asked for, if it asked for one. */
        fun of(query: SearchQuery): DateConstraint = DateConstraint(
            year = query.year,
            month = query.month,
            day = query.day,
        )
    }
}
