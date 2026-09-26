package com.memorymap.ui.memories

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.memorymap.R
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaType
import com.memorymap.navigation.Routes
import com.memorymap.ui.common.emotionLabel
import com.memorymap.ui.common.formatLong
import com.memorymap.ui.common.rememberAudioPlayer
import com.memorymap.ui.common.rememberLocale
import com.memorymap.ui.common.visibilityLabel
import com.memorymap.util.MediaImporter
import com.memorymap.ui.common.VideoPlayer
import java.io.File

/**
 * One memory with everything attached to it.
 *
 * Photos are shown as a grid, audio plays back locally, and video plays through
 * the system player. None of it is analysed: a video is a file the user stored.
 */
@Composable
fun MemoryDetailScreen(
    navController: NavHostController,
    memoryId: String,
    viewModel: MemoryDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = rememberLocale()
    val audio = rememberAudioPlayer()
    var confirmDelete by remember { mutableStateOf(false) }

    val memory = state.memory
    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) navController.popBackStack()
    }

    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (memory == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.memory_not_found))
        }
        return
    }

    val photos = state.attachments.filter { it.type == MediaType.PHOTO }
    val audioItems = state.attachments.filter { it.type == MediaType.AUDIO }
    val videos = state.attachments.filter { it.type == MediaType.VIDEO }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = stringResource(R.string.action_cancel),
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { navController.navigate(Routes.memoryEditor(memory.id)) }) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = stringResource(R.string.action_edit),
                    )
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                    )
                }
            }
        }

        item {
            Column {
                Text(memory.title, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = {},
                        label = { Text(memory.memoryDate.formatLong(locale)) },
                    )
                    AssistChip(onClick = {}, label = { Text(emotionLabel(memory.emotion)) })
                    AssistChip(onClick = {}, label = { Text(visibilityLabel(memory.visibility)) })
                }
                if (!memory.placeName.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = memory.placeName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (memory.text.isNotBlank()) {
            item { Text(memory.text, style = MaterialTheme.typography.bodyLarge) }
        }

        if (photos.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.memory_section_photos, photos.size)) }
            items(photos, key = { "photo-${it.id}" }) { photo ->
                AsyncImage(
                    model = File(photo.uri),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium),
                )
            }
        }

        if (audioItems.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.memory_section_audio, audioItems.size)) }
            items(audioItems, key = { "audio-${it.id}" }) { item ->
                AudioRow(
                    item = item,
                    isPlaying = audio.isPlaying && audio.currentPath == item.uri,
                    positionMs = if (audio.currentPath == item.uri) audio.positionMs else 0L,
                    onPlayPause = { audio.play(item.uri) },
                    onDelete = { viewModel.removeAttachment(item) },
                )
            }
        }

        if (videos.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.memory_section_videos, videos.size)) }
            items(videos, key = { "video-${it.id}" }) { item ->
                VideoCard(
                    item = item,
                    onDelete = { viewModel.removeAttachment(item) },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.memory_delete_title)) },
            text = { Text(stringResource(R.string.memory_delete_body, memory.title)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete()
                        confirmDelete = false
                    },
                ) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun AudioRow(
    item: MediaItem,
    isPlaying: Boolean,
    positionMs: Long,
    onPlayPause: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.memory_action_pause else R.string.memory_action_play,
                    ),
                )
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.media_type_audio),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${MediaImporter.formatDuration(positionMs)} / ${MediaImporter.formatDuration(item.durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                )
            }
        }
    }
}

@Composable
private fun VideoCard(item: MediaItem, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.media_type_video), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            VideoPlayer(
                path = item.uri,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(MaterialTheme.shapes.medium),
            )
        }
    }
}
