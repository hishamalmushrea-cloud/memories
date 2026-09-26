package com.memorymap.ui.camera

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The lamp settings the in-app camera offers. */
enum class CameraFlash { OFF, AUTO, ON }

/** The two lenses the in-app camera can use. */
enum class CameraLens { BACK, FRONT }

/** What the shutter button does. */
enum class CameraMode { PHOTO, VIDEO }

/**
 * The settings the user can change while the in-app camera is open.
 *
 * The class holds no CameraX types on purpose, so the behaviour can be unit
 * tested without a device: [flashMode] and [selector] are the only places where
 * a setting turns into a library constant.
 */
@Stable
class CameraControls(
    initialLens: CameraLens = CameraLens.BACK,
    initialFlash: CameraFlash = CameraFlash.AUTO,
    initialMode: CameraMode = CameraMode.PHOTO,
) {

    var lens by mutableStateOf(initialLens)
        private set

    var mode by mutableStateOf(initialMode)
        private set

    var flash by mutableStateOf(if (initialLens == CameraLens.BACK) initialFlash else CameraFlash.OFF)
        private set

    /**
     * Only the back lens has a lamp, and only a still photo uses one: the video
     * use case has no flash mode to set.
     */
    val canToggleFlash: Boolean get() = lens == CameraLens.BACK && mode == CameraMode.PHOTO

    /** Remembered per lens, so turning the camera round is not a reset. */
    private var flashOnBackLens: CameraFlash =
        if (initialLens == CameraLens.BACK) initialFlash else CameraFlash.AUTO

    /** Steps through off, automatic and on again. A lens without a lamp does not step. */
    fun cycleFlash() {
        if (!canToggleFlash) return
        flash = when (flash) {
            CameraFlash.OFF -> CameraFlash.AUTO
            CameraFlash.AUTO -> CameraFlash.ON
            CameraFlash.ON -> CameraFlash.OFF
        }
        flashOnBackLens = flash
    }

    /** Turns the camera around, leaving the lamp the way the back lens had it. */
    fun flipLens() {
        if (lens == CameraLens.BACK) {
            flashOnBackLens = flash
            lens = CameraLens.FRONT
            flash = CameraFlash.OFF
        } else {
            lens = CameraLens.BACK
            flash = flashOnBackLens
        }
    }

    /**
     * Switches between taking one photo and recording a video. The lamp setting is
     * left alone: it belongs to the next photo, and the video use case has no
     * flash mode to give it to.
     */
    fun toggleMode() {
        mode = if (mode == CameraMode.PHOTO) CameraMode.VIDEO else CameraMode.PHOTO
    }
}

/**
 * When a finished recording is worth keeping.
 *
 * A press and release of the record button is a mis-tap; a recording the camera
 * itself gave up on is not a video either, and the half-written file behind it
 * is thrown away.
 */
object VideoTakePolicy {

    /** Below this a recording is an accident, not a video. */
    const val MINIMUM_DURATION_MS: Long = 1_000L

    fun keep(durationMs: Long, failed: Boolean): Boolean =
        !failed && durationMs >= MINIMUM_DURATION_MS
}

/** The CameraX constant that belongs to a lamp setting. */
val CameraFlash.flashMode: Int
    get() = when (this) {
        CameraFlash.OFF -> ImageCapture.FLASH_MODE_OFF
        CameraFlash.AUTO -> ImageCapture.FLASH_MODE_AUTO
        CameraFlash.ON -> ImageCapture.FLASH_MODE_ON
    }

/** The CameraX selector that belongs to a lens. */
val CameraLens.selector: CameraSelector
    get() = if (this == CameraLens.FRONT) {
        CameraSelector.DEFAULT_FRONT_CAMERA
    } else {
        CameraSelector.DEFAULT_BACK_CAMERA
    }
