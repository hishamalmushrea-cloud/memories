package com.memorymap.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memorymap.ui.common.rememberLocale
import com.memorymap.R
import com.memorymap.domain.model.DayContentCounts
import com.memorymap.domain.usecase.DiaryTime
import com.memorymap.navigation.Routes
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * The month page: a calendar where a dot means "this day has content" and a star
 * means "this day has a memory", plus the month counters above it.
 */
@Composable
fun MonthScreen(
    navController: NavHostController,
    year: Int,
    month: Int,
    viewModel: DiaryPeriodViewModel = hiltViewModel(),
) {
    val locale = rememberLocale()
    val yearMonth = remember(year, month) { YearMonth.of(year.coerceAtLeast(1970), month.coerceIn(1, 12)) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(yearMonth) { viewModel.observeMonth(yearMonth.atDay(1)) }

    val weekdayNames = remember(locale) { DiaryTime.weekdayNames(locale).map { it.take(3) } }
    val weeks = remember(yearMonth) { DiaryTime.weeksOfMonth(yearMonth) }
    val countsByDay = remember(state.counts, yearMonth) {
        DiaryTime.daysWithContent(state.counts, yearMonth)
    }
    val totals = remember(state.counts) { state.counts.fold(MonthTotals()) { acc, c -> acc + c } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "${DiaryTime.monthNames(locale)[yearMonth.monthValue - 1]} ${yearMonth.year}",
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            text = stringResource(R.string.month_recorded_days, countsByDay.size),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = stringResource(R.string.month_totals, totals.entries, totals.photos, totals.videos, totals.audio, totals.memories),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

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
                            .clickable(enabled = day != null) {
                                day?.let { navController.navigate(Routes.day(DiaryTime.isoDate(it))) }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (day != null) {
                            val counts = countsByDay[day.dayOfMonth]
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${day.dayOfMonth}", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.height(2.dp))
                                DayMarker(counts)
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

/** Simple accumulator so the month header can show real totals. */
private class MonthTotals(
    val entries: Int = 0,
    val photos: Int = 0,
    val videos: Int = 0,
    val audio: Int = 0,
    val memories: Int = 0,
) {
    operator fun plus(c: DayContentCounts) = MonthTotals(
        entries = entries + c.entries,
        photos = photos + c.photos,
        videos = videos + c.videos,
        audio = audio + c.audio,
        memories = memories + c.memories,
    )
}
