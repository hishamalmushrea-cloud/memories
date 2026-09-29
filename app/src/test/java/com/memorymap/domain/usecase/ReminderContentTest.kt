package com.memorymap.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The daily reminder's one decision, driven directly - no Android, no database, no clock.
 *
 * The rule that matters is the priority and the silence: a day that has something to
 * resurface from earlier years leads with it, a day that does not and is unwritten
 * invites the user to write, and a day already written earns no notification at all -
 * because a reminder that fires every day regardless is noise the user learns to ignore.
 */
class ReminderContentTest {

    @Test
    fun `earlier years today lead with what they hold`() {
        val decision = ReminderContent.decide(onThisDayCount = 3, wroteToday = false)
        assertEquals(ReminderContent.Decision.OnThisDay(3), decision)
    }

    @Test
    fun `something to resurface outranks an unwritten today`() {
        // Even with today still blank, the memories from this day in earlier years are
        // the more meaningful thing to surface.
        val decision = ReminderContent.decide(onThisDayCount = 1, wroteToday = false)
        assertEquals(ReminderContent.Decision.OnThisDay(1), decision)
    }

    @Test
    fun `something to resurface is shown even when today is already written`() {
        val decision = ReminderContent.decide(onThisDayCount = 2, wroteToday = true)
        assertEquals(ReminderContent.Decision.OnThisDay(2), decision)
    }

    @Test
    fun `nothing to resurface and an unwritten today invites the user to write`() {
        val decision = ReminderContent.decide(onThisDayCount = 0, wroteToday = false)
        assertEquals(ReminderContent.Decision.WriteToday, decision)
    }

    @Test
    fun `nothing to resurface and a day already written stays silent`() {
        val decision = ReminderContent.decide(onThisDayCount = 0, wroteToday = true)
        assertEquals(ReminderContent.Decision.Nothing, decision)
    }
}
