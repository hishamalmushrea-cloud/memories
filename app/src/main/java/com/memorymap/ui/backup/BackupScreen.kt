package com.memorymap.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.memorymap.R
import com.memorymap.ui.common.rememberLocale
import com.memorymap.util.backup.BackupCounts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Export and import of a local archive.
 *
 * Both actions start from a folder the user picks, so the app never holds a copy
 * of their life in a place only the app can reach. An import shows what it found
 * and asks before merging anything.
 */
@Composable
fun BackupScreen(viewModel: BackupViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.export(uri.toString())
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) viewModel.inspect(uri.toString())
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = stringResource(R.string.backup_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Button(
            onClick = { exportPicker.launch(null) },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_action_export))
        }
        OutlinedButton(
            onClick = { importPicker.launch(null) },
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.backup_action_import))
        }

        if (state.isBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.backup_running),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        val messageKey = state.messageKey
        val messageId = messageKey?.let { backupMessageId(it) }
        if (messageId != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(messageId), style = MaterialTheme.typography.bodyMedium)
                    state.lastExport?.let { counts -> CountsBlock(counts) }
                    state.lastImport?.let { counts -> CountsBlock(counts) }
                    if (state.mediaCopied > 0) {
                        Text(
                            text = stringResource(R.string.backup_media_copied, state.mediaCopied),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.mediaMissing > 0) {
                        Text(
                            text = stringResource(R.string.backup_media_missing, state.mediaMissing),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.mediaRestored > 0) {
                        Text(
                            text = stringResource(R.string.backup_media_restored, state.mediaRestored),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (state.skipped > 0) {
                        Text(
                            text = stringResource(R.string.backup_skipped, state.skipped),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = viewModel::dismissMessage, modifier = Modifier.align(Alignment.End)) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
        }
    }

    val manifest = state.pendingManifest
    if (manifest != null) {
        val locale = rememberLocale()
        val createdAt = Instant.ofEpochMilli(manifest.createdAtEpochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale))

        AlertDialog(
            onDismissRequest = viewModel::cancelImport,
            title = { Text(stringResource(R.string.backup_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.backup_confirm_body))
                    Text(stringResource(R.string.backup_created_at, createdAt))
                    CountsBlock(manifest.counts)
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImport) {
                    Text(stringResource(R.string.backup_action_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImport) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** The same seven counts the profile statistics show, so the labels are familiar. */
@Composable
private fun CountsBlock(counts: BackupCounts) {
    Column {
        CountRow(stringResource(R.string.stat_memories), counts.memories)
        CountRow(stringResource(R.string.stat_events), counts.dailyEntries)
        CountRow(stringResource(R.string.stat_people), counts.people)
        CountRow(stringResource(R.string.stat_places), counts.places)
        CountRow(stringResource(R.string.stat_photos), counts.photos)
        CountRow(stringResource(R.string.stat_audio), counts.audio)
        CountRow(stringResource(R.string.stat_videos), counts.videos)
    }
}

@Composable
private fun CountRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value.toString(), style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * Maps the outcome keys the repository returns to resources.
 *
 * An unknown key resolves to nothing rather than to a plausible-sounding error:
 * inventing a reason would be worse than showing none.
 */
private fun backupMessageId(key: String): Int? = when (key) {
    "backup_exported" -> R.string.backup_exported
    "backup_imported" -> R.string.backup_imported
    "backup_error_open" -> R.string.backup_error_open
    "backup_error_write" -> R.string.backup_error_write
    "backup_error_format" -> R.string.backup_error_format
    else -> null
}
