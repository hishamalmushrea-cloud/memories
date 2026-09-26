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
    fun `every lamp setting maps to its own CameraX constant`() {
        assertEquals(ImageCapture.FLASH_MODE_OFF, CameraFlash.OFF.flashMode)
        assertEquals(ImageCapture.FLASH_MODE_AUTO, CameraFlash.AUTO.flashMode)
        assertEquals(ImageCapture.FLASH_MODE_ON, CameraFlash.ON.flashMode)

        assertNotEquals(CameraFlash.OFF.flashMode, CameraFlash.AUTO.flashMode)
        assertNotEquals(CameraFlash.AUTO.flashMode, CameraFlash.ON.flashMode)
        assertNotEquals(CameraFlash.OFF.flashMode, CameraFlash.ON.flashMode)
    }
}
