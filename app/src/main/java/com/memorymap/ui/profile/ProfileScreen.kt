package com.memorymap.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.memorymap.R
import com.memorymap.domain.model.AuthState
import androidx.navigation.NavHostController
import com.memorymap.domain.model.SyncOutcome
import com.memorymap.domain.model.SyncState
import com.memorymap.ui.common.emotionLabel
import com.memorymap.ui.common.formatDateTime
import com.memorymap.ui.common.rememberLocale
import com.memorymap.domain.model.LifeStats
import com.memorymap.navigation.Routes

/**
 * Account header, the life statistics, and the session actions.
 *
 * Signing out ends the session only. The local archive is never deleted by a
 * sign-out: wiping data is a separate, explicitly confirmed action.
 */
@Composable
fun ProfileScreen(
    navController: NavHostController,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val signedIn = state.authState as? AuthState.SignedIn

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = signedIn?.user?.displayName ?: stringResource(R.string.profile_local_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = signedIn?.user?.email ?: stringResource(R.string.profile_local_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (signedIn?.offlineAccount == true || !state.cloudConfigured) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.profile_offline_only),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (state.cloudConfigured) {
            SyncCard(sync = state.sync, onSyncNow = viewModel::syncNow)
        }

        state.stats?.let { stats -> StatsCard(stats, state.peopleCount) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    text = stringResource(R.string.backup_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { navController.navigate(Routes.BACKUP) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.backup_action_open))
                }
            }
        }

        OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.auth_action_sign_out))
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.wipe_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.wipe_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = viewModel::requestDeleteLocalData,
                    enabled = !state.isWiping,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (state.isWiping) R.string.wipe_running else R.string.wipe_action))
                }
            }
        }
    }

    if (state.wipeConfirmationVisible) {
        // The choice about the uploaded copies defaults to leaving them. The
        // button's promise is "on this device", and ticking this would spend
        // bytes that may still be the only copy another device can fetch.
        var deleteCloudCopies by rememberSaveable { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = viewModel::cancelDeleteLocalData,
            title = { Text(stringResource(R.string.wipe_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.wipe_confirm_body))
                    if (state.uploadedCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = deleteCloudCopies,
                                onCheckedChange = { deleteCloudCopies = it },
                            )
                            Text(
                                text = stringResource(
                                    R.string.wipe_cloud_choice,
                                    state.uploadedCount,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = stringResource(R.string.wipe_cloud_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDeleteLocalData(deleteCloudCopies) }) {
                    Text(stringResource(R.string.wipe_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDeleteLocalData) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    state.wipeSummary?.let { summary ->
        AlertDialog(
            onDismissRequest = viewModel::dismissWipeSummary,
            title = { Text(stringResource(R.string.wipe_done_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(
                            R.string.wipe_done_body,
                            summary.totalRecords,
                            summary.mediaFiles,
                        ),
                    )
                    // Said here, and not only on the way in: whatever is left is
                    // now unreachable from the app.
                    state.cloudRemoval?.let { cloud ->
                        if (cloud.removed > 0) {
                            Text(
                                stringResource(R.string.wipe_cloud_done, cloud.removed),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (cloud.remaining > 0) {
                            Text(
                                stringResource(R.string.wipe_cloud_left, cloud.remaining),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissWipeSummary) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun StatsCard(stats: LifeStats, peopleCount: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.profile_stats_title), style = MaterialTheme.typography.titleMedium)
            StatRow(stringResource(R.string.stat_recorded_days), stats.recordedDays.toString())
            StatRow(stringResource(R.string.stat_memories), stats.memories.toString())
            StatRow(stringResource(R.string.stat_events), stats.events.toString())
            StatRow(stringResource(R.string.stat_places), stats.places.toString())
            StatRow(stringResource(R.string.stat_people), peopleCount.toString())
            StatRow(stringResource(R.string.stat_photos), stats.photos.toString())
            StatRow(stringResource(R.string.stat_audio), stats.audio.toString())
            StatRow(stringResource(R.string.stat_videos), stats.videos.toString())
            val topEmotion = stats.topEmotion
            val topEmotionText = if (topEmotion != null) {
                emotionLabel(topEmotion)
            } else {
                stringResource(R.string.stat_none)
            }
            val topMonthText = stats.topMonth
                ?.let { "${it.year}-${it.month.toString().padStart(2, '0')}" }
                ?: stringResource(R.string.stat_none)
            StatRow(stringResource(R.string.stat_top_emotion), topEmotionText)
            StatRow(stringResource(R.string.stat_top_month), topMonthText)
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/**
 * Where the offline queue stands.
 *
 * This is the only place the user can see synchronisation at all, so it says
 * plainly how much is waiting and whether the last attempt worked — a silent
 * sync is indistinguishable from a broken one.
 */
@Composable
private fun SyncCard(sync: SyncState, onSyncNow: () -> Unit) {
    val locale = rememberLocale()

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.profile_sync_title), style = MaterialTheme.typography.titleMedium)

            Text(
                text = stringResource(R.string.sync_pending_count, sync.pending),
                style = MaterialTheme.typography.bodyMedium,
            )

            sync.lastOutcome?.let { outcome ->
                Text(
                    text = when (outcome) {
                        SyncOutcome.OK -> stringResource(R.string.sync_last_ok)
                        SyncOutcome.NOTHING_TO_DO -> stringResource(R.string.sync_last_nothing)
                        else -> stringResource(R.string.sync_last_error)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            formatDateTime(sync.lastRunAt, locale)?.let { stamp ->
                Text(
                    text = stringResource(R.string.sync_last_run_at, stamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(
                onClick = onSyncNow,
                enabled = !sync.isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (sync.isRunning) R.string.sync_running else R.string.sync_action_now))
            }
        }
    }
}
