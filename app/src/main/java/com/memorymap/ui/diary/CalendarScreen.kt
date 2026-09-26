package com.memorymap.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memorymap.R
import com.memorymap.domain.usecase.DiaryTime
import com.memorymap.navigation.Routes
import com.memorymap.ui.common.rememberLocale
import java.time.LocalDate
import java.time.YearMonth

/**
 * The calendar browser: move between months and jump to any day.
 *
 * The month page answers "what did this month look like"; this answers "take me
 * to a day". It reuses the same grid and the same per-day counters, so a dot here
 * means exactly what a dot means there.
 */
@Composable
fun CalendarScreen(
    navController: NavHostController,
    viewModel: DiaryPeriodViewModel = hiltViewModel(),
) {
    val locale = rememberLocale()
    var yearMonth by rememberSaveable { mutableStateOf(YearMonth.now()) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(yearMonth) { viewModel.observeMonth(yearMonth.atDay(1)) }

    val countsByDay = remember(state.counts, yearMonth) {
        DiaryTime.daysWithContent(state.counts, yearMonth)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { yearMonth = yearMonth.minusMonths(1) }) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.calendar_previous_month),
                )
            }
            Text(
                text = "${DiaryTime.monthNames(locale)[yearMonth.monthValue - 1]} ${yearMonth.year}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { yearMonth = yearMonth.plusMonths(1) }) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = stringResource(R.string.calendar_next_month),
                )
            }
        }

        Text(
            text = stringResource(R.string.month_recorded_days, countsByDay.size),
            style = MaterialTheme.typography.bodyMedium,
        )

        MonthCalendarGrid(
            yearMonth = yearMonth,
            locale = locale,
            countsByDay = countsByDay,
            onDayClick = { day -> navController.navigate(Routes.day(DiaryTime.isoDate(day))) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(4.dp))

        OutlinedButton(
            onClick = {
                val today = LocalDate.now()
                yearMonth = YearMonth.from(today)
                navController.navigate(Routes.day(DiaryTime.isoDate(today)))
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.calendar_go_to_today))
        }
    }
}
