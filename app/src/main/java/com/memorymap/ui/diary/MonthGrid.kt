package com.memorymap.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.memorymap.domain.model.DayContentCounts
import com.memorymap.domain.usecase.DiaryTime
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * One month as a calendar grid.
 *
 * Shared by the month page and the calendar browser so both draw the same weeks,
 * the same content markers and open the same day screen. A dot means the day has
 * content, a star means it has a memory.
 *
 * The week starts on Sunday regardless of locale, matching `DiaryTime`, so the
 * columns line up with the counts query.
 */
@Composable
fun MonthCalendarGrid(
    yearMonth: YearMonth,
    locale: Locale,
    countsByDay: Map<Int, DayContentCounts>,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val weekdayNames = remember(locale) { DiaryTime.weekdayNames(locale).map { it.take(3) } }
    val weeks = remember(yearMonth) { DiaryTime.weeksOfMonth(yearMonth) }

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            weekdayNames.forEach { name ->
                Box(
                    Modifier
                        .weight(1f)
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth()) {
                // The first and last week of a month are clipped, so a slot can be
                // empty; an empty slot renders nothing instead of a wrong day.
                repeat(7) { slot ->
                    val day = week.getOrNull(slot)
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clickable(enabled = day != null) { day?.let(onDayClick) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (day != null) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${day.dayOfMonth}", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(2.dp))
                                DayMarker(countsByDay[day.dayOfMonth])
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Dot for content, star for a memory. Deliberately minimal to stay uncluttered. */
@Composable
private fun DayMarker(counts: DayContentCounts?) {
    if (counts == null || !counts.hasContent) {
        Spacer(Modifier.size(6.dp))
        return
    }
    if (counts.memories > 0) {
        Text("★", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    } else {
        Box(
            Modifier
                .size(6.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
    }
}
