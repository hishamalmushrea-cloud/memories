package com.memorymap.ui.diary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memorymap.ui.common.rememberLocale
import com.memorymap.R
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.usecase.DiaryTime
import com.memorymap.navigation.Routes
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The day screen: "what did I do today?" at the top, the ordered log of the
 * day's events below, and a slot for the day's media in Phase 3.
 */
@Composable
fun DayScreen(
    navController: NavHostController,
    date: String,
    viewModel: DayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = rememberLocale()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = DiaryTime.dayHeader(state.date, locale),
            style = MaterialTheme.typography.headlineSmall,
        )

        Button(
            onClick = { navController.navigate(Routes.entryEditor(DiaryTime.isoDate(state.date))) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.diary_add_to_day))
        }

        DayNoteCard(
            initial = state.diaryNote,
            onSave = viewModel::saveDiaryNote,
        )

        Text(
            text = stringResource(R.string.diary_day_events),
            style = MaterialTheme.typography.titleMedium,
        )

        if (state.entries.isEmpty()) {
            Text(
                text = stringResource(R.string.diary_no_events),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.entries.forEach { entry ->
                EventRow(
                    entry = entry,
                    onClick = {
                        navController.navigate(
                            Routes.entryEditor(DiaryTime.isoDate(state.date), entry.id),
                        )
                    },
                    onDelete = { viewModel.deleteEntry(entry.id) },
                )
            }
        }

        if (state.onThisDay.isNotEmpty()) {
            Divider()
            Text(
                text = stringResource(R.string.on_this_day),
                style = MaterialTheme.typography.titleMedium,
            )
            state.onThisDay.forEach { item ->
                Text(
                    text = "${item.year}  ·  ${item.title}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun DayNoteCard(
    initial: String,
    onSave: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.diary_note_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                placeholder = { Text(stringResource(R.string.diary_note_hint)) },
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onSave(text) }) {
                Text(stringResource(R.string.action_save))
            }
        }
    }
}

@Composable
private fun EventRow(entry: DailyEntry, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = entry.time.format(DateTimeFormatter.ofPattern("HH:mm")),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(entry.title, style = MaterialTheme.typography.titleSmall)
            if (entry.text.isNotBlank()) {
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = stringResource(R.string.action_delete),
            )
        }
    }
}
