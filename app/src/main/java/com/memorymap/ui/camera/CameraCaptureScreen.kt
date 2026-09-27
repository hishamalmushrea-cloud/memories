package com.memorymap.ui.camera

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.memorymap.R
import com.memorymap.domain.model.MediaType
import com.memorymap.ui.common.CAMERA_PERMISSION
import com.memorymap.ui.common.RECORD_AUDIO_PERMISSION
import com.memorymap.util.MediaImporter
import com.memorymap.util.MediaStore
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Takes a photo or records a video with the camera inside the app.
 *
 * The result is written straight into the app-private archive, so no storage
 * permission is needed and no other app is handed the file. The caller is given
 * the file with its media type and MIME type and registers it like any other
 * attachment; a photo is compressed and stripped of location metadata only if it
 * is later uploaded.
 *
 * A recording is plain media: it is never transcribed, analysed or summarised.
 * Sound is captured only when the microphone permission is already held; without
 * it the video is recorded silently rather than asking for a permission in the
 * middle of a shot.
 *
 * The camera is unbound when this leaves the composition, so it is never held
 * open behind another screen.
 */
@Composable
fun CameraCaptureScreen(
    ownerId: String,
    onCaptured: (File, MediaType, String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val controls = remember { CameraControls() }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val captured by rememberUpdatedState(onCaptured)

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var photo by remember { mutableStateOf<ImageCapture?>(null) }
    var video by remember { mutableStateOf<VideoCapture<Recorder>?>(null) }
    var status by remember { mutableStateOf(CameraStatus.PREPARING) }
    var busy by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf<Recording?>(null) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var ticker by remember { mutableStateOf<Job?>(null) }
    var closed by remember { mutableStateOf(false) }

    /** Builds a recorder, optionally asking for a quality the camera may refuse. */
    fun recorder(quality: QualitySelector?): Recorder {
        val builder = Recorder.Builder()
        if (quality != null) builder.setQualitySelector(quality)
        return builder.build()
    }

    /**
     * Decides what happens to a recording that has just finished: an error or a
     * mis-tap is deleted, anything longer is handed to the editor. A recording
     * that finished after the screen was left is deleted too, so nothing lands in
     * the archive that no row points at.
     */
    fun onRecordEvent(event: VideoRecordEvent, file: File) {
        if (event !is VideoRecordEvent.Finalize) return
        ticker?.cancel()
        ticker = null
        recording = null
        busy = false
        val duration = elapsedMs
        elapsedMs = 0L

        if (closed) {
            MediaStore.delete(file)
            return
        }
        if (VideoTakePolicy.keep(duration, event.hasError())) {
            captured(file, MediaType.VIDEO, VIDEO_MIME)
        } else {
            MediaStore.delete(file)
            status = if (event.hasError()) CameraStatus.RECORD_FAILED else CameraStatus.TOO_SHORT
        }
    }

    fun startRecording() {
        val useCase = video ?: return
        if (recording != null) return

        val file = MediaStore.newFile(context, MediaType.VIDEO, ownerId, "mp4")
        val options = FileOutputOptions.Builder(file).build()
        val pending = useCase.output.prepareRecording(context, options)

        // `withAudioEnabled` needs the microphone permission, so the check is made
        // here rather than from a helper: the permission is asked for by the screen
        // that owns the voice notes, and a shot is not interrupted to request it.
        val canRecordSound =
            ContextCompat.checkSelfPermission(context, RECORD_AUDIO_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        val request = if (canRecordSound) pending.withAudioEnabled(false) else pending

        val started = runCatching {
            request.start(executor) { event -> onRecordEvent(event, file) }
        }.getOrNull()

        if (started == null) {
            MediaStore.delete(file)
            status = CameraStatus.RECORD_FAILED
            return
        }

        recording = started
        elapsedMs = 0L
        val began = SystemClock.elapsedRealtime()
        ticker = scope.launch {
            while (isActive) {
                elapsedMs = SystemClock.elapsedRealtime() - began
                delay(200L)
            }
        }
    }

    /** Stops the recording; the file is kept or thrown away when it is finalised. */
    fun stopRecording() {
        val active = recording ?: return
        busy = true
        // Freezing the clock here is what the length is judged by, not the moment
        // the camera gets around to finalising the file.
        ticker?.cancel()
        ticker = null
        runCatching { active.stop() }
    }

    fun takePhoto() {
        val useCase = photo ?: return
        if (busy) return
        busy = true
        // The photo is rotated the way the phone is held, not the way the sensor is.
        useCase.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val file = MediaStore.newFile(context, MediaType.PHOTO, ownerId, "jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        useCase.takePicture(
            options,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    busy = false
                    captured(file, MediaType.PHOTO, PHOTO_MIME)
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    MediaStore.delete(file)
                    status = CameraStatus.CAPTURE_FAILED
                }
            },
        )
    }

    // Back leaves the camera, not the editor behind it. Mid-recording it stops
    // the recording first, so the shot is kept rather than thrown away.
    BackHandler(enabled = true) {
        if (recording != null) stopRecording() else onCancel()
    }

    LaunchedEffect(controls.lens, controls.mode, previewView, lifecycleOwner) {
        status = CameraStatus.PREPARING
        photo = null
        video = null
        if (!hasCameraPermission(context)) {
            status = CameraStatus.PERMISSION_DENIED
            return@LaunchedEffect
        }

        val bound = runCatching { awaitCameraProvider(context) }.getOrNull()
        if (bound == null) {
            status = CameraStatus.UNAVAILABLE
            return@LaunchedEffect
        }
        provider = bound

        val selector = controls.lens.selector
        if (!bound.hasCamera(selector)) {
            status = CameraStatus.UNAVAILABLE
            return@LaunchedEffect
        }

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val ready = runCatching {
            bound.unbindAll()
            if (controls.mode == CameraMode.PHOTO) {
                val useCase = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                useCase.flashMode = controls.flash.flashMode
                bound.bindToLifecycle(lifecycleOwner, selector, preview, useCase)
                photo = useCase
            } else {
                // HD first; a camera that refuses it gets the default selector,
                // which asks the device what it can actually record.
                val useCase = runCatching {
                    val hd = VideoCapture.withOutput(recorder(QualitySelector.from(Quality.HD)))
                    bound.bindToLifecycle(lifecycleOwner, selector, preview, hd)
                    hd
                }.recoverCatching {
                    val any = VideoCapture.withOutput(recorder(null))
                    bound.bindToLifecycle(lifecycleOwner, selector, preview, any)
                    any
                }.getOrThrow()
                video = useCase
            }
        }.isSuccess

        status = if (ready) CameraStatus.READY else CameraStatus.UNAVAILABLE
    }

    // Changing the lamp does not need a new binding: the use case follows it.
    LaunchedEffect(controls.flash, photo) {
        photo?.flashMode = controls.flash.flashMode
    }

    // Leaving stops the recording and releases the camera.
    DisposableEffect(previewView) {
        onDispose {
            closed = true
            ticker?.cancel()
            ticker = null
            runCatching { recording?.close() }
            runCatching { provider?.unbindAll() }
        }
    }

    val soundOff = controls.mode == CameraMode.VIDEO && !hasMicrophonePermission(context)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (recording != null) stopRecording() else onCancel() }) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            ModeButton(
                icon = Icons.Outlined.PhotoCamera,
                label = R.string.camera_mode_photo,
                selected = controls.mode == CameraMode.PHOTO,
                enabled = recording == null,
                onClick = controls::toggleMode,
            )
            ModeButton(
                icon = Icons.Outlined.Videocam,
                label = R.string.camera_mode_video,
                selected = controls.mode == CameraMode.VIDEO,
                enabled = recording == null,
                onClick = controls::toggleMode,
            )
            if (controls.canToggleFlash) {
                IconButton(onClick = controls::cycleFlash) {
                    Icon(
                        controls.flash.icon,
                        contentDescription = stringResource(controls.flash.label),
                        tint = Color.White,
                    )
                }
            }
            IconButton(onClick = controls::flipLens, enabled = recording == null) {
                Icon(
                    Icons.Outlined.FlipCameraAndroid,
                    contentDescription = stringResource(R.string.camera_action_flip),
                    tint = Color.White,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            status.message?.let { message ->
                Text(
                    text = stringResource(message),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (recording != null) {
                Text(
                    text = MediaImporter.formatDuration(elapsedMs),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.camera_recording),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (soundOff) {
                Text(
                    text = stringResource(R.string.camera_video_without_sound),
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            ShutterButton(
                mode = controls.mode,
                recording = recording != null,
                busy = busy,
                enabled = status == CameraStatus.READY,
                onPhoto = { takePhoto() },
                onRecord = { startRecording() },
                onStop = { stopRecording() },
            )
        }
    }
}

/** The shutter: a camera button for photos, a record button for video. */
@Composable
private fun ShutterButton(
    mode: CameraMode,
    recording: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onPhoto: () -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
) {
    val label = when {
        mode == CameraMode.PHOTO -> R.string.camera_action_capture
        recording -> R.string.camera_action_stop_recording
        else -> R.string.camera_action_record
    }
    val action = when {
        mode == CameraMode.PHOTO -> onPhoto
        recording -> onStop
        else -> onRecord
    }

    if (mode == CameraMode.PHOTO && busy) {
        CircularProgressIndicator(color = Color.White)
        return
    }

    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .clickable(
                enabled = enabled && !busy,
                onClickLabel = stringResource(label),
                onClick = action,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (mode == CameraMode.PHOTO) {
            Icon(
                Icons.Outlined.PhotoCamera,
                contentDescription = null,
                tint = Color.White,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(if (recording) 28.dp else 56.dp)
                    .clip(if (recording) RoundedCornerShape(6.dp) else CircleShape)
                    .background(RecordingRed),
            )
        }
    }
}

/** One of the two capture-mode buttons in the top row. */
@Composable
private fun ModeButton(
    icon: ImageVector,
    label: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled) {
        Icon(
            icon,
            contentDescription = stringResource(label),
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
        )
    }
}

/** What the capture screen is doing right now. */
private enum class CameraStatus(val message: Int?) {
    PREPARING(R.string.camera_status_preparing),
    READY(null),
    PERMISSION_DENIED(R.string.camera_error_permission),
    UNAVAILABLE(R.string.camera_error_unavailable),
    CAPTURE_FAILED(R.string.camera_error_capture),
    RECORD_FAILED(R.string.camera_error_record),
    TOO_SHORT(R.string.camera_error_too_short),
}

private val RecordingRed = Color(0xFFE53935)

/** A CameraX JPEG, and the MP4 the recorder writes. */
private const val PHOTO_MIME = "image/jpeg"
private const val VIDEO_MIME = "video/mp4"

private val CameraFlash.icon: ImageVector
    get() = when (this) {
        CameraFlash.OFF -> Icons.Outlined.FlashOff
        CameraFlash.AUTO -> Icons.Outlined.FlashAuto
        CameraFlash.ON -> Icons.Outlined.FlashOn
    }

private val CameraFlash.label: Int
    get() = when (this) {
        CameraFlash.OFF -> R.string.camera_flash_off
        CameraFlash.AUTO -> R.string.camera_flash_auto
        CameraFlash.ON -> R.string.camera_flash_on
    }

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED

/** Whether a video recorded now could carry sound at all. */
private fun hasMicrophonePermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, RECORD_AUDIO_PERMISSION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Waits for the camera provider without blocking the main thread.
 *
 * A missing or broken camera provider comes back as a failure, which the caller
 * turns into "no camera on this device" rather than a crash.
 */
private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { bound -> continuation.resume(bound) }
                    .onFailure { failure -> continuation.resumeWithException(failure) }
            },
            ContextCompat.getMainExecutor(context),
        )
    }
