package com.memorymap.domain.usecase

import com.memorymap.domain.model.OnThisDayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's one decision, driven directly - no Android, no RemoteViews, no database.
 *
 * Two rules matter here. The first is the count: a day with items from earlier years
 * shows how many, and a day without any shows the empty state. The second is privacy:
 * the widget lives on the home screen, visible to whoever holds the phone, so it may
 * carry a number but never a title - the archive can be locked and its records kept
 * out of sight, and the home screen must not become the place they leak.
 */
class WidgetContentTest {

    @Test
    fun `no items today is the empty state`() {
        assertEquals(WidgetContent.State.Empty, WidgetContent.from(emptyList()))
    }

    @Test
    fun `a single item from an earlier year counts as one`() {
        val items = listOf(OnThisDayItem(year = 2019, title = "The old road", isMemory = true))
        assertEquals(WidgetContent.State.OnThisDay(1), WidgetContent.from(items))
    }

    @Test
    fun `memories and notes alike are counted together`() {
        // The widget does not distinguish a memory from a note; both are something
        // this day held, so both count.
        val items = listOf(
            OnThisDayItem(year = 2018, title = "A morning", isMemory = true),
            OnThisDayItem(year = 2021, title = "A note", isMemory = false),
            OnThisDayItem(year = 2024, title = "Another", isMemory = true),
        )
        assertEquals(WidgetContent.State.OnThisDay(3), WidgetContent.from(items))
    }

    @Test
    fun `the state carries a count and never a title`() {
        // The privacy guarantee, stated as a test: whatever the items were called,
        // only the number crosses into the widget's state.
        val items = listOf(
            OnThisDayItem(year = 2016, title = "Something private", isMemory = true),
            OnThisDayItem(year = 2020, title = "Something else", isMemory = false),
        )
        val state = WidgetContent.from(items)
        assertTrue(state is WidgetContent.State.OnThisDay)
        assertEquals(2, (state as WidgetContent.State.OnThisDay).count)
    }
}
