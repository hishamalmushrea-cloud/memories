package com.memorymap.ui.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.memorymap.ui.common.rememberLocale
import com.memorymap.R
import com.memorymap.domain.usecase.DiaryTime
import com.memorymap.navigation.Routes
import java.time.LocalDate

/**
 * Entry point of the diary. It answers one question: how far back do you want to
 * go? Day -> week -> month -> year, exactly as the browsing model is defined.
 */
@Composable
fun DiaryHomeScreen(navController: NavHostController) {
    val locale = rememberLocale()
    val today = remember { LocalDate.now() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = DiaryTime.dayHeader(today, locale),
            style = MaterialTheme.typography.headlineSmall,
        )

        DiaryJumpCard(
            icon = Icons.Outlined.Today,
            title = stringResource(R.string.diary_today),
            subtitle = DiaryTime.dayHeader(today, locale),
            onClick = { navController.navigate(Routes.day(DiaryTime.isoDate(today))) },
        )
        DiaryJumpCard(
            icon = Icons.Outlined.Event,
            title = stringResource(R.string.diary_yesterday),
            subtitle = DiaryTime.dayHeader(today.minusDays(1), locale),
            onClick = { navController.navigate(Routes.day(DiaryTime.isoDate(today.minusDays(1)))) },
        )
        DiaryJumpCard(
            icon = Icons.Outlined.Schedule,
            title = stringResource(R.string.diary_this_week),
            subtitle = DiaryTime.weekLabel(today, locale),
            onClick = { navController.navigate(Routes.week(DiaryTime.isoDate(today))) },
        )
        DiaryJumpCard(
            icon = Icons.Outlined.CalendarMonth,
            title = stringResource(R.string.diary_this_month),
            subtitle = DiaryTime.monthNames(locale)[today.monthValue - 1] + " " + today.year,
            onClick = { navController.navigate(Routes.month(today.year, today.monthValue)) },
        )
        DiaryJumpCard(
            icon = Icons.Outlined.History,
            title = stringResource(R.string.diary_this_year),
            subtitle = today.year.toString(),
            onClick = { navController.navigate(Routes.year(today.year)) },
        )
        DiaryJumpCard(
            icon = Icons.Outlined.DateRange,
            title = stringResource(R.string.diary_calendar),
            subtitle = stringResource(R.string.diary_calendar_hint),
            onClick = { navController.navigate(Routes.CALENDAR) },
        )
    }
}

@Composable
private fun DiaryJumpCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}
