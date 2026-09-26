package com.memorymap.ui.memories

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import com.memorymap.ui.common.LinkOption
import com.memorymap.ui.common.LinkPicker
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaType
import com.memorymap.navigation.Routes
import com.memorymap.domain.model.Visibility
import com.memorymap.ui.camera.CameraCaptureScreen
import com.memorymap.ui.common.CAMERA_PERMISSION
import com.memorymap.ui.common.RECORD_AUDIO_PERMISSION
import com.memorymap.ui.common.emotionLabel
import com.memorymap.ui.common.formatLong
import com.memorymap.ui.common.rememberAudioRecorder
import com.memorymap.ui.common.rememberLocale
import com.memorymap.ui.common.rememberPermissionRequest
import com.memorymap.ui.common.visibilityLabel
import com.memorymap.util.MediaImporter
import java.io.File
import java.time.Instant
import java.util.Locale
import java.time.ZoneOffset

/**
 * Creates a memory or edits an existing one.
 *
 * Attachments are copied into app-private storage as soon as they are added and
 * are only written to the database on save; abandoning the editor deletes them.
 * Camera and microphone permissions are requested at the moment of use, never
 * up front.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MemoryEditorScreen(
    navController: NavHostController,
    viewModel: MemoryEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = rememberLocale()
    val recorder = rememberAudioRecorder()

    var showDatePicker by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) navController.popBackStack()
    }

    // --- Media sources ---

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::importFromUri) }

    val requestCamera = rememberPermissionRequest(CAMERA_PERMISSION) { showCamera = true }

    val requestMicrophone = rememberPermissionRequest(RECORD_AUDIO_PERMISSION) {
        recorder.start(state.memoryId)
    }

    // The camera runs inside the app: no photo is handed to another app, and the
    // picture is written straight into the private archive while it is taken.
    if (showCamera) {
        CameraCaptureScreen(
            ownerId = state.memoryId,
            onCaptured = { file, type, mime ->
                showCamera = false
                viewModel.adoptCapture(file, type, mime)
            },
            onCancel = { showCamera = false },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showDiscardDialog = true }) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_cancel),
                )
            }
            Text(
                text = stringResource(
                    if (state.isNew) R.string.memory_editor_new else R.string.memory_editor_edit,
                ),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        state.errorRes?.let { errorRes ->
            Text(
                text = stringResource(errorRes),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        OutlinedTextField(
            value = state.title,
            onValueChange = viewModel::onTitleChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.memory_field_title)) },
        )

        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            label = { Text(stringResource(R.string.memory_field_text)) },
        )

        // --- Date ---
        OutlinedButton(
            onClick = { showDatePicker = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.memory_field_date, state.date.formatLong(locale)))
        }

        // --- Emotion ---
        Text(stringResource(R.string.memory_field_emotion), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Emotion.entries.forEach { emotion ->
                FilterChip(
                    selected = state.emotion == emotion,
                    onClick = { viewModel.onEmotionChange(emotion) },
                    label = { Text(emotionLabel(emotion)) },
                )
            }
        }

        // --- Visibility ---
        Text(stringResource(R.string.memory_field_visibility), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Visibility.entries.forEach { visibility ->
                FilterChip(
                    selected = state.visibility == visibility,
                    onClick = { viewModel.onVisibilityChange(visibility) },
                    label = { Text(visibilityLabel(visibility)) },
                )
            }
        }

        OutlinedTextField(
            value = state.placeName,
            onValueChange = viewModel::onPlaceNameChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.memory_field_place)) },
        )

        // --- People and places ---
        LinkPicker(
            label = stringResource(R.string.editor_people_label),
            options = state.people.map { LinkOption(it.id, it.name) },
            selectedIds = state.personIds,
            onToggle = viewModel::togglePerson,
            canCreate = true,
            onCreate = viewModel::createPerson,
            emptyLabel = stringResource(R.string.editor_people_empty),
            createFieldLabel = stringResource(R.string.editor_person_add),
            createActionLabel = stringResource(R.string.editor_add_action),
        )

        LinkPicker(
            label = stringResource(R.string.editor_places_label),
            options = state.places.map { LinkOption(it.id, it.name) },
            selectedIds = state.placeIds,
            onToggle = viewModel::togglePlace,
            // A place needs coordinates, which only the memory's own pin can supply.
            canCreate = state.location != null,
            onCreate = viewModel::createPlace,
            emptyLabel = stringResource(
                if (state.location == null) R.string.editor_places_needs_location
                else R.string.editor_places_empty,
            ),
            createFieldLabel = stringResource(R.string.editor_place_add),
            createActionLabel = stringResource(R.string.editor_add_action),
        )

        // --- Location ---
        OutlinedButton(
            onClick = {
                navController.navigate(
                    Routes.locationPicker(state.location?.latitude, state.location?.longitude),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Place, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = state.location?.let {
                    stringResource(
                        R.string.memory_location_set,
                        String.format(Locale.US, "%.5f", it.latitude),
                        String.format(Locale.US, "%.5f", it.longitude),
                    )
                } ?: stringResource(R.string.memory_pick_location),
                maxLines = 1,
            )
        }
        if (state.location != null) {
            TextButton(onClick = viewModel::clearLocation, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.memory_clear_location))
            }
        }

        // --- Attachments ---
        Text(
            text = stringResource(R.string.memory_section_attachments),
            style = MaterialTheme.typography.titleSmall,
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
            ) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.memory_action_pick_photo))
            }
            OutlinedButton(onClick = requestCamera) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.memory_action_take_photo))
            }
            OutlinedButton(
                onClick = {
                    photoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
                },
            ) {
                Icon(Icons.Outlined.Videocam, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.memory_action_pick_video))
            }
        }

        // --- Voice note ---
        if (recorder.isRecording) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(
                                R.string.memory_recording,
                                MediaImporter.formatDuration(recorder.elapsedMs),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            recorder.stop()?.let { viewModel.adoptCapture(it, MediaType.AUDIO, "audio/mp4") }
                        }) {
                            Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.memory_action_keep))
                        }
                        OutlinedButton(onClick = { recorder.cancel() }) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            }
        } else {
            OutlinedButton(onClick = requestMicrophone) {
                Icon(Icons.Outlined.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.memory_action_record))
            }
        }

        // --- Current attachments ---
        state.attachments.forEach { item ->
            AttachmentRow(
                item = item,
                onRemove = { viewModel.removeAttachment(item) },
                onToggleUpload = { viewModel.toggleUpload(item) },
            )
        }

        Button(
            onClick = viewModel::save,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (state.isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.action_save))
        }

        if (!state.isNew) {
            OutlinedButton(
                onClick = viewModel::delete,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_delete))
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = state.date
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // The picker reports UTC midnight, so it is read back in
                        // UTC too; otherwise the date shifts a day east of GMT.
                        pickerState.selectedDateMillis?.let { millis ->
                            viewModel.onDateChange(
                                Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }
                        showDatePicker = false
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text(stringResource(R.string.memory_discard_title)) },
            text = { Text(stringResource(R.string.memory_discard_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        navController.popBackStack()
                    },
                ) { Text(stringResource(R.string.memory_action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun AttachmentRow(
    item: MediaItem,
    onRemove: () -> Unit,
    onToggleUpload: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (item.type) {
                MediaType.PHOTO -> AsyncImage(
                    model = File(item.uri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialTheme.shapes.small),
                )

                else -> Icon(
                    if (item.type == MediaType.AUDIO) Icons.Outlined.GraphicEq else Icons.Outlined.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = File(item.uri).name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                if (item.durationMs != null) {
                    Text(
                        text = MediaImporter.formatDuration(item.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Three states, and only two of them are a choice: an attachment
                // that is already in the cloud shows what it is, and one that is
                // not offers the decision. Nothing uploads without a tap here.
                when {
                    item.isUploaded -> Text(
                        text = stringResource(R.string.editor_media_uploaded),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    item.uploadRequested -> Text(
                        text = stringResource(R.string.editor_media_uploading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Text(
                        text = stringResource(R.string.editor_media_upload),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onToggleUpload, enabled = !item.isUploaded) {
                Icon(
                    if (item.isUploaded) Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload,
                    contentDescription = stringResource(
                        if (item.uploadRequested) {
                            R.string.editor_media_upload_cancel
                        } else {
                            R.string.editor_media_upload
                        },
                    ),
                    tint = if (item.uploadRequested || item.isUploaded) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                )
            }
        }
    }
}
