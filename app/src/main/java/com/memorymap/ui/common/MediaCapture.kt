package com.memorymap.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.memorymap.domain.model.MediaType
import com.memorymap.util.MediaStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Asks for a dangerous permission the first time it is needed.
 *
 * Location, camera and microphone are requested on demand only, at the moment
 * the user taps the action that needs them. Nothing is requested at startup and
 * nothing is tracked in the background.
 *
 * The returned lambda runs [onGranted] immediately when the permission is
 * already held, and otherwise launches the system dialog and runs it only if the
 * user agrees.
 */
@Composable
fun rememberPermissionRequest(permission: String, onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onGranted()
    }
    return {
        val alreadyGranted = ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) onGranted() else launcher.launch(permission)
    }
}

/** True when a dangerous permission is currently granted. */
@Composable
fun hasPermission(permission: String): Boolean {
    val context = LocalContext.current
    return ContextCompat.checkSelfPermission(context, permission) ==
        PackageManager.PERMISSION_GRANTED
}

/**
 * Records a voice note into the app-private archive.
 *
 * A recording is a plain audio file: it is never transcribed and never analysed.
 */
@Stable
class AudioRecorderController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    var isRecording by mutableStateOf(false)
        private set

    var elapsedMs by mutableLongStateOf(0L)
        private set

    private var recorder: MediaRecorder? = null
    private var target: File? = null
    private var ticker: Job? = null

    /**
     * Starts recording into a new file owned by [ownerId]. Returns false when the
     * device refused to start, in which case no file is left behind.
     */
    fun start(ownerId: String): Boolean {
        if (isRecording) return false
        val file = MediaStore.newFile(context, MediaType.AUDIO, ownerId, "m4a")
        val candidate = newRecorder()
        val started = runCatching {
            candidate.setAudioSource(MediaRecorder.AudioSource.MIC)
            candidate.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            candidate.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            candidate.setOutputFile(file.absolutePath)
            candidate.prepare()
            candidate.start()
        }.isSuccess

        if (!started) {
            runCatching { candidate.release() }
            MediaStore.delete(file)
            return false
        }

        recorder = candidate
        target = file
        isRecording = true
        elapsedMs = 0L
        val startedAt = SystemClock.elapsedRealtime()
        ticker = scope.launch {
            while (isActive) {
                elapsedMs = SystemClock.elapsedRealtime() - startedAt
                delay(200L)
            }
        }
        return true
    }

    /** Stops recording and returns the file, or null when it is too short to keep. */
    fun stop(): File? {
        val file = target
        val tooShort = elapsedMs < MINIMUM_RECORDING_MS
        release()
        if (file == null) return null
        if (tooShort || !file.exists() || file.length() == 0L) {
            MediaStore.delete(file)
            return null
        }
        return file
    }

    /** Stops recording and throws the file away. */
    fun cancel() {
        val file = target
        release()
        file?.let { MediaStore.delete(it) }
    }

    /** Releases the recorder so the microphone is never held open. */
    fun release() {
        ticker?.cancel()
        ticker = null
        runCatching {
            if (isRecording) recorder?.stop()
            recorder?.release()
        }
        recorder = null
        target = null
        isRecording = false
        elapsedMs = 0L
    }

    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

    private companion object {
        /** Below this a tap is an accident, not a voice note. */
        const val MINIMUM_RECORDING_MS = 700L
    }
}

/** Creates an [AudioRecorderController] tied to this composition. */
@Composable
fun rememberAudioRecorder(): AudioRecorderController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context) { AudioRecorderController(context, scope) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

/** The permission needed to record a voice note. */
const val RECORD_AUDIO_PERMISSION: String = Manifest.permission.RECORD_AUDIO

/** The permission needed to take a photo with the camera. */
const val CAMERA_PERMISSION: String = Manifest.permission.CAMERA
