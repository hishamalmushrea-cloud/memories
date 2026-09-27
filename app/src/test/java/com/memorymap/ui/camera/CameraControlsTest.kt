package com.memorymap.ui.camera

import androidx.camera.core.ImageCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera settings are the part of the capture screen that can be checked
 * without a device: which lens is used, what the lamp does, and which CameraX
 * constant each setting turns into.
 */
class CameraControlsTest {

    @Test
    fun `the back lens and an automatic flash are the defaults`() {
        val controls = CameraControls()

        assertEquals(CameraLens.BACK, controls.lens)
        assertEquals(CameraFlash.AUTO, controls.flash)
        assertTrue(controls.canToggleFlash)
    }

    @Test
    fun `the lamp steps off, automatic, on, off`() {
        val controls = CameraControls(initialFlash = CameraFlash.OFF)

        controls.cycleFlash()
        assertEquals(CameraFlash.AUTO, controls.flash)

        controls.cycleFlash()
        assertEquals(CameraFlash.ON, controls.flash)

        controls.cycleFlash()
        assertEquals(CameraFlash.OFF, controls.flash)
    }

    @Test
    fun `a front lens starts with no lamp and cannot switch one on`() {
        val controls = CameraControls(initialLens = CameraLens.FRONT, initialFlash = CameraFlash.ON)

        assertEquals(CameraFlash.OFF, controls.flash)
        assertFalse(controls.canToggleFlash)

        controls.cycleFlash()
        assertEquals(CameraFlash.OFF, controls.flash)
    }

    @Test
    fun `turning the camera round keeps the lamp for the way back`() {
        val controls = CameraControls(initialFlash = CameraFlash.ON)

        controls.flipLens()
        assertEquals(CameraLens.FRONT, controls.lens)
        assertEquals(CameraFlash.OFF, controls.flash)

        controls.flipLens()
        assertEquals(CameraLens.BACK, controls.lens)
        assertEquals(CameraFlash.ON, controls.flash)
    }

    @Test
    fun `a lamp step taken on the front lens is not carried to the back`() {
        val controls = CameraControls(initialFlash = CameraFlash.OFF)

        controls.flipLens()
        controls.cycleFlash()
        assertEquals(CameraFlash.OFF, controls.flash)

        controls.flipLens()
        assertEquals(CameraFlash.OFF, controls.flash)
    }

    @Test
    fun `the camera opens ready for a photo`() {
        assertEquals(CameraMode.PHOTO, CameraControls().mode)
    }

    @Test
    fun `switching the mode goes photo, video, photo`() {
        val controls = CameraControls()

        controls.toggleMode()
        assertEquals(CameraMode.VIDEO, controls.mode)

        controls.toggleMode()
        assertEquals(CameraMode.PHOTO, controls.mode)
    }

    @Test
    fun `the lamp is not offered while the mode is video`() {
        val controls = CameraControls(initialFlash = CameraFlash.ON)

        controls.toggleMode()
        assertFalse(controls.canToggleFlash)

        controls.cycleFlash()
        assertEquals(CameraFlash.ON, controls.flash)
    }

    @Test
    fun `the lamp is still where it was left when the photo mode returns`() {
        val controls = CameraControls(initialFlash = CameraFlash.ON)

        controls.toggleMode()
        controls.toggleMode()

        assertTrue(controls.canToggleFlash)
        assertEquals(CameraFlash.ON, controls.flash)
    }

    @Test
    fun `turning the camera round does not change the mode`() {
        val controls = CameraControls(initialMode = CameraMode.VIDEO)

        controls.flipLens()

        assertEquals(CameraMode.VIDEO, controls.mode)
    }

    @Test
    fun `a recording has to last a second to be worth keeping`() {
        assertFalse(VideoTakePolicy.keep(999L, failed = false))
        assertTrue(VideoTakePolicy.keep(1_000L, failed = false))
        assertTrue(VideoTakePolicy.keep(5_000L, failed = false))
    }

    @Test
    fun `a recording the camera gave up on is never kept`() {
        assertFalse(VideoTakePolicy.keep(10_000L, failed = true))
    }

    @Test
    fun `every lamp setting maps to its own CameraX constant`() {
        assertEquals(ImageCapture.FLASH_MODE_OFF, CameraFlash.OFF.flashMode)
        assertEquals(ImageCapture.FLASH_MODE_AUTO, CameraFlash.AUTO.flashMode)
        assertEquals(ImageCapture.FLASH_MODE_ON, CameraFlash.ON.flashMode)

        assertNotEquals(CameraFlash.OFF.flashMode, CameraFlash.AUTO.flashMode)
        assertNotEquals(CameraFlash.AUTO.flashMode, CameraFlash.ON.flashMode)
        assertNotEquals(CameraFlash.OFF.flashMode, CameraFlash.ON.flashMode)
    }
}
