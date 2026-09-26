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
) {

    var lens by mutableStateOf(initialLens)
        private set

    var flash by mutableStateOf(if (initialLens == CameraLens.BACK) initialFlash else CameraFlash.OFF)
        private set

    /** Only the back lens has a lamp; the front one has nothing to switch on. */
    val canToggleFlash: Boolean get() = lens == CameraLens.BACK

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
