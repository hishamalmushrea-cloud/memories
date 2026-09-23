package com.memorymap.ui.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memorymap.ui.common.rememberLocale
import com.memorymap.R
import com.memorymap.domain.usecase.DiaryTime
import com.memorymap.navigation.Routes
import java.util.Locale

/**
 * The year page: twelve months with their counters, each opening the month view.
 * Every number is counted from stored rows.
 */
@Composable
fun YearScreen(
    navController: NavHostController,
    year: Int,
    viewModel: DiaryPeriodViewModel = hiltViewModel(),
) {
    val locale = rememberLocale()
    val safeYear = year.coerceAtLeast(1970)
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(safeYear) { viewModel.observeYear(safeYear) }

    val monthNames = remember(locale) { DiaryTime.monthNames(locale) }
    val monthCounts = remember(state.counts, safeYear) {
        DiaryTime.monthsWithContent(state.counts, safeYear).associateBy { it.month }
    }
    val recordedDays = remember(state.counts) { state.counts.count { it.hasContent } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = safeYear.toString(), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.year_recorded_days, recordedDays),
            style = MaterialTheme.typography.bodyMedium,
        )

        monthNames.forEachIndexed { index, name ->
            val monthValue = index + 1
            val count = monthCounts[monthValue]?.count ?: 0
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { navController.navigate(Routes.month(safeYear, monthValue)) },
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.year_days_count, count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
