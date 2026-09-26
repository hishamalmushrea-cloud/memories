package com.memorymap.ui.camera

import android.content.Context
import android.content.pm.PackageManager
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FlashAuto
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.FlipCameraAndroid
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.memorymap.util.MediaStore
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Takes a photo with the camera inside the app.
 *
 * The picture is written straight into the app-private archive, so no storage
 * permission is needed and no other app is handed the file. The photo is
 * returned as the [File] that already holds it; the caller registers it like any
 * other attachment, and it is compressed and stripped of location metadata only
 * if it is later uploaded.
 *
 * The camera is unbound when this leaves the composition, so it is never held
 * open behind another screen.
 */
@Composable
fun CameraCaptureScreen(
    ownerId: String,
    onCaptured: (File) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controls = remember { CameraControls() }
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val captured by rememberUpdatedState(onCaptured)

    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var status by remember { mutableStateOf(CameraStatus.PREPARING) }
    var busy by remember { mutableStateOf(false) }

    // Back leaves the camera, not the editor behind it.
    BackHandler(enabled = true, onBack = onCancel)

    LaunchedEffect(controls.lens, previewView, lifecycleOwner) {
        status = CameraStatus.PREPARING
        capture = null
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

        val useCase = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        useCase.flashMode = controls.flash.flashMode
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        val ready = runCatching {
            bound.unbindAll()
            bound.bindToLifecycle(lifecycleOwner, selector, preview, useCase)
        }.isSuccess

        if (ready) {
            capture = useCase
            status = CameraStatus.READY
        } else {
            status = CameraStatus.UNAVAILABLE
        }
    }

    // Changing the lamp does not need a new binding: the use case follows it.
    LaunchedEffect(controls.flash, capture) {
        capture?.flashMode = controls.flash.flashMode
    }

    // The camera is released the moment this screen is gone.
    DisposableEffect(previewView) {
        onDispose { runCatching { provider?.unbindAll() } }
    }

    fun takePhoto() {
        val useCase = capture ?: return
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
                    captured(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    busy = false
                    MediaStore.delete(file)
                    status = CameraStatus.CAPTURE_FAILED
                }
            },
        )
    }

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
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.action_cancel),
                    tint = Color.White,
                )
            }
            Spacer(Modifier.weight(1f))
            if (controls.canToggleFlash) {
                IconButton(onClick = controls::cycleFlash) {
                    Icon(
                        controls.flash.icon,
                        contentDescription = stringResource(controls.flash.label),
                        tint = Color.White,
                    )
                }
            }
            IconButton(onClick = controls::flipLens) {
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

            if (busy) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .border(3.dp, Color.White, CircleShape)
                        .clickable(enabled = status == CameraStatus.READY, onClick = { takePhoto() }),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = stringResource(R.string.camera_action_capture),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/** What the capture screen is doing right now. */
private enum class CameraStatus(val message: Int?) {
    PREPARING(R.string.camera_status_preparing),
    READY(null),
    PERMISSION_DENIED(R.string.camera_error_permission),
    UNAVAILABLE(R.string.camera_error_unavailable),
    CAPTURE_FAILED(R.string.camera_error_capture),
}

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
