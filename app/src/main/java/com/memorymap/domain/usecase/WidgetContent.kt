package com.memorymap.domain.usecase

import com.memorymap.domain.model.OnThisDayItem

/**
 * What the home-screen widget shows for today.
 *
 * The widget is a glanceable: it answers "is there anything from this day worth
 * going back to?" without the user opening the app. The answer is a *count*, never
 * a title - the archive can be locked and its records private, so the home screen
 * must not become a place where a kept memory leaks into view for whoever is
 * holding the phone. A number is useful and reveals nothing.
 *
 * This is pure on purpose: the provider gathers the day's items from the local
 * database and hands them here, so the rule is ordinary code a plain JVM test can
 * drive with no Android, no RemoteViews and no database.
 */
object WidgetContent {

    sealed interface State {
        /** Nothing from this day in earlier years yet. */
        data object Empty : State

        /**
         * [count] records - memories and notes alike - exist for this month-day in
         * earlier years. Only the number crosses into the widget; no title does.
         */
        data class OnThisDay(val count: Int) : State
    }

    /**
     * @param items what [com.memorymap.domain.repository.OnThisDayRepository] returned
     *   for today, already limited to earlier years.
     */
    fun from(items: List<OnThisDayItem>): State =
        if (items.isEmpty()) State.Empty else State.OnThisDay(items.size)
}
