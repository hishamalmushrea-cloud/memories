package com.memorymap.ui.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memorymap.R
import com.memorymap.domain.model.DayContentCounts
import com.memorymap.domain.usecase.DiaryTime
import com.memorymap.navigation.Routes
import java.time.LocalDate
import java.util.Locale

/**
 * The week page: seven rows, Sunday first, each with the day's counters, and a
 * tap opens that day's diary.
 */
@Composable
fun WeekScreen(
    navController: NavHostController,
    date: String,
    viewModel: DiaryPeriodViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale: Locale = remember(LocalConfiguration.current) {
        LocalConfiguration.current.locales?.get(0) ?: Locale.getDefault()
    }
    val anchor = remember(date) { runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now()) }
    val weekdayNames = remember(locale) { DiaryTime.weekdayNames(locale) }

    LaunchedEffect(anchor) { viewModel.observeWeek(anchor) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = DiaryTime.weekLabel(anchor, locale),
            style = MaterialTheme.typography.headlineSmall,
        )

        DiaryTime.weekDays(anchor).forEachIndexed { index, day ->
            val counts = state.counts.firstOrNull { it.date == day }
            WeekDayRow(
                weekdayName = weekdayNames[index],
                day = day,
                counts = counts,
                onClick = { navController.navigate(Routes.day(DiaryTime.isoDate(day))) },
            )
        }
    }
}

@Composable
private fun WeekDayRow(
    weekdayName: String,
    day: LocalDate,
    counts: DayContentCounts?,
    onClick: () -> Unit,
) {
    Card(Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(96.dp)) {
                Text(weekdayName, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "${day.dayOfMonth}/${day.monthValue}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.week_events_count, counts?.entries ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.week_media_count,
                        counts?.photos ?: 0,
                        counts?.audio ?: 0,
                        counts?.videos ?: 0,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
