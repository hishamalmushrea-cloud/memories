package com.memorymap.ui.common

import android.content.Context
import android.media.MediaPlayer
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.memorymap.util.MmLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays one local audio attachment at a time.
 *
 * Playback is plain local playback of the file the user stored. Nothing is
 * streamed, transcribed or analysed.
 */
@Stable
class AudioPlayerController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    var isPlaying by mutableStateOf(false)
        private set

    var positionMs by mutableLongStateOf(0L)
        private set

    var durationMs by mutableLongStateOf(0L)
        private set

    /** Path of the attachment currently loaded, so the UI can highlight it. */
    var currentPath by mutableStateOf<String?>(null)
        private set

    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** Loads [path] and starts it, stopping whatever was playing before. */
    fun play(path: String) {
        if (isPlaying && currentPath == path) {
            pause()
            return
        }
        stopTicker()
        runCatching { player?.release() }
        val candidate = MediaPlayer()
        val prepared = runCatching {
            candidate.setDataSource(path)
            candidate.prepare()
            candidate.start()
        }.isSuccess

        if (!prepared) {
            runCatching { candidate.release() }
            player = null
            currentPath = null
            isPlaying = false
            return
        }

        player = candidate
        currentPath = path
        durationMs = candidate.duration.coerceAtLeast(0).toLong()
        positionMs = 0L
        isPlaying = true
        candidate.setOnCompletionListener {
            isPlaying = false
            positionMs = 0L
            stopTicker()
        }
        ticker = scope.launch {
            while (isActive) {
                runCatching { player?.currentPosition?.toLong() }
                    .getOrNull()
                    ?.let { positionMs = it }
                delay(200L)
            }
        }
    }

    fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
            .onFailure { MmLog.e("Unable to pause playback", it) }
        isPlaying = false
        stopTicker()
    }

    fun seekTo(positionMs: Long) {
        runCatching { player?.seekTo(positionMs.toInt()) }
            .onSuccess { this.positionMs = positionMs }
            .onFailure { MmLog.e("Unable to seek", it) }
    }

    /** Stops playback and releases the decoder. */
    fun release() {
        stopTicker()
        runCatching { player?.release() }
        player = null
        isPlaying = false
        positionMs = 0L
        durationMs = 0L
        currentPath = null
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }
}

/** Creates an [AudioPlayerController] that is released with the composition. */
@Composable
fun rememberAudioPlayer(): AudioPlayerController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context) { AudioPlayerController(context, scope) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

/**
 * A local video attachment.
 *
 * The system [VideoView] is used so no extra player dependency is needed, and
 * the file never leaves the device. Playback starts only when the user presses
 * play on the on-screen controls.
 */
@Composable
fun VideoPlayer(
    path: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { context ->
            FrameLayout(context).apply {
                val view = VideoView(context)
                val controls = MediaController(context)
                controls.setAnchorView(view)
                view.setMediaController(controls)
                addView(
                    view,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                view.setVideoPath(path)
            }
        },
        update = { container ->
            val view = container.getChildAt(0) as? VideoView ?: return@AndroidView
            if (view.videoPath != path) view.setVideoPath(path)
        },
        onRelease = { container ->
            (container.getChildAt(0) as? VideoView)?.stopPlayback()
        },
    )
}
