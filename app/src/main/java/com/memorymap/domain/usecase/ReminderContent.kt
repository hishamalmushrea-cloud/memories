package com.memorymap.domain.usecase

/**
 * What the daily reminder should say, or whether it should stay quiet.
 *
 * A reminder that fires every day no matter what is noise the user learns to
 * ignore, and an ignored reminder is worse than none. So the content is a
 * decision, not a constant: the most meaningful thing first - what the user
 * wrote on this day in earlier years, which is the whole reason a memory map
 * keeps dates - and only when there is nothing to resurface, a nudge to write
 * today. A day that already has an entry earns silence.
 *
 * This is pure on purpose: the worker gathers the two facts (how many earlier
 * years have something today, whether today is already written) and hands them
 * here, so the rule is ordinary code a plain JVM test can drive with no Android,
 * no database and no clock.
 */
object ReminderContent {

    sealed interface Decision {
        /** Say nothing; the user already wrote today and there is nothing to resurface. */
        data object Nothing : Decision

        /** Resurface what this day held in earlier years. */
        data class OnThisDay(val count: Int) : Decision

        /** Nothing to resurface and today is unwritten: invite the user to write it. */
        data object WriteToday : Decision
    }

    /**
     * @param onThisDayCount how many records exist for this month-day in *earlier* years.
     * @param wroteToday whether the user already wrote a diary note today.
     */
    fun decide(onThisDayCount: Int, wroteToday: Boolean): Decision = when {
        onThisDayCount > 0 -> Decision.OnThisDay(onThisDayCount)
        !wroteToday -> Decision.WriteToday
        else -> Decision.Nothing
    }
}
