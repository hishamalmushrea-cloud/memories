package com.memorymap.domain.usecase

import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.Memory
import java.time.LocalDate
import java.time.LocalTime

/**
 * One row of the life timeline.
 *
 * A diary event and a memory are both "something that happened on a day", so the
 * timeline shows them in one stream. The difference is only whether it has a
 * clock time: an event happened at a moment, a memory belongs to the whole day.
 */
data class TimelineRow(
    val id: String,
    val title: String,
    val text: String,
    val time: LocalTime?,
    val emotion: Emotion?,
    val isMemory: Boolean,
)

/** One day of the timeline, newest day first, rows ordered within the day. */
data class TimelineDay(
    val date: LocalDate,
    val rows: List<TimelineRow>,
)

/**
 * Merges diary events and memories into the life timeline.
 *
 * Pure on purpose: no Android and no database, so the ordering rules are tested
 * on the JVM. Everything shown here was written by the user; nothing is
 * generated or inferred.
 */
object TimelineBuilder {

    /**
     * Builds the timeline.
     *
     * Days are newest first. Inside a day the timed events come first in clock
     * order and the day's memories follow, so reading a day goes morning to
     * evening and finishes with what the day meant.
     */
    fun build(events: List<DailyEntry>, memories: List<Memory>): List<TimelineDay> {
        val liveEvents = events.filter { !it.isDeleted }
        val liveMemories = memories.filter { !it.isDeleted }

        val eventsByDay = liveEvents.groupBy { it.date }
        val memoriesByDay = liveMemories.groupBy { it.memoryDate }

        return (eventsByDay.keys + memoriesByDay.keys)
            .distinct()
            .sortedDescending()
            .map { day ->
                val timed = eventsByDay[day].orEmpty().sortedBy { it.time }.map {
                    TimelineRow(
                        id = it.id,
                        title = it.title,
                        text = it.text,
                        time = it.time.toLocalTime(),
                        emotion = it.emotion,
                        isMemory = false,
                    )
                }
                val untimed = memoriesByDay[day].orEmpty().sortedBy { it.title }.map {
                    TimelineRow(
                        id = it.id,
                        title = it.title,
                        text = it.text,
                        time = null,
                        emotion = it.emotion,
                        isMemory = true,
                    )
                }
                TimelineDay(date = day, rows = timed + untimed)
            }
    }

    /**
     * The oldest day of a timeline window of [days] days ending on [today].
     *
     * The diary answers "what happened in this week, month or year"; the timeline
     * answers "what happened recently" and grows a window at a time.
     */
    fun windowStart(today: LocalDate, days: Int): LocalDate =
        today.minusDays((days - 1).coerceAtLeast(0).toLong())
}
