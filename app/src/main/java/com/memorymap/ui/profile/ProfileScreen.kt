package com.memorymap.ui.profile

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.memorymap.R
import com.memorymap.domain.model.AuthState
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.LifeStats

/**
 * Account header, the life statistics, and the session actions.
 *
 * Signing out ends the session only. The local archive is never deleted by a
 * sign-out: wiping data is a separate, explicitly confirmed action.
 */
@Composable
fun ProfileScreen(
    @Suppress("UNUSED_PARAMETER") navController: androidx.navigation.NavHostController,
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

        state.stats?.let { stats -> StatsCard(stats, state.peopleCount) }

        OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.auth_action_sign_out))
        }
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
 * Maps an emotion to its display name. The Arabic labels live in the default
 * `values/strings.xml`, so this stays a resource lookup rather than a hardcoded
 * Arabic string in Kotlin.
 */
@Composable
private fun emotionLabel(emotion: Emotion): String = when (emotion) {
    Emotion.HAPPY -> stringResource(R.string.emotion_happy)
    Emotion.SAD -> stringResource(R.string.emotion_sad)
    Emotion.LOVE -> stringResource(R.string.emotion_love)
    Emotion.FEAR -> stringResource(R.string.emotion_fear)
    Emotion.PRIDE -> stringResource(R.string.emotion_pride)
    Emotion.NOSTALGIA -> stringResource(R.string.emotion_nostalgia)
}
