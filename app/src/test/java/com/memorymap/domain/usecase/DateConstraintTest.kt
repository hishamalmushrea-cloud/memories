package com.memorymap.domain.usecase

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which dates a result may have.
 *
 * The point of these is the case a range cannot express: a month with no year
 * means that month in *every* year, and a test that only checked a range would
 * never have caught it being wrong.
 */
class DateConstraintTest {

    @Test
    fun `no constraint accepts anything`() {
        val constraint = DateConstraint()

        assertTrue(constraint.isUnconstrained)
        assertTrue(constraint.matches(LocalDate.of(1998, 3, 17)))
        assertTrue(constraint.matches(LocalDate.of(2026, 9, 24)))
    }

    @Test
    fun `a month alone matches that month in every year`() {
        val september = DateConstraint(month = 9)

        assertTrue(september.matches(LocalDate.of(2019, 9, 1)))
        assertTrue(september.matches(LocalDate.of(2026, 9, 30)))
        assertFalse(september.matches(LocalDate.of(2026, 8, 31)))
        assertFalse(september.matches(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun `a year alone matches the whole year`() {
        val year = DateConstraint(year = 2024)

        assertTrue(year.matches(LocalDate.of(2024, 1, 1)))
        assertTrue(year.matches(LocalDate.of(2024, 12, 31)))
        assertFalse(year.matches(LocalDate.of(2023, 12, 31)))
        assertFalse(year.matches(LocalDate.of(2025, 1, 1)))
    }

    @Test
    fun `a month and a year together pin one month`() {
        val constraint = DateConstraint(year = 2024, month = 9)

        assertTrue(constraint.matches(LocalDate.of(2024, 9, 15)))
        assertFalse(constraint.matches(LocalDate.of(2025, 9, 15)))
        assertFalse(constraint.matches(LocalDate.of(2024, 8, 15)))
    }

    @Test
    fun `a full date matches exactly one day`() {
        val constraint = DateConstraint(year = 2019, month = 3, day = 15)

        assertTrue(constraint.matches(LocalDate.of(2019, 3, 15)))
        assertFalse(constraint.matches(LocalDate.of(2019, 3, 16)))
        assertFalse(constraint.matches(LocalDate.of(2020, 3, 15)))
    }

    @Test
    fun `every part that is set has to hold`() {
        // A day with no month or year is "the 15th of any month" - unusual, but
        // it is what was asked for, and silently widening it would be worse.
        val dayOnly = DateConstraint(day = 15)

        assertTrue(dayOnly.matches(LocalDate.of(2001, 7, 15)))
        assertFalse(dayOnly.matches(LocalDate.of(2001, 7, 14)))
    }

    @Test
    fun `the constraint is built from what the query asked`() {
        val constraint = DateConstraint.of(SearchQueryParser.parse("15 مارس 2019"))

        assertFalse(constraint.isUnconstrained)
        assertTrue(constraint.matches(LocalDate.of(2019, 3, 15)))
        assertFalse(constraint.matches(LocalDate.of(2019, 3, 16)))
    }

    @Test
    fun `a query with no date leaves the constraint open`() {
        assertTrue(DateConstraint.of(SearchQueryParser.parse("اجتماع العمل")).isUnconstrained)
    }
}
