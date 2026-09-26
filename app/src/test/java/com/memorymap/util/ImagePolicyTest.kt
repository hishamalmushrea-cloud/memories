package com.memorymap.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers a photo is held to before it is allowed into the cloud.
 *
 * The sizes are worth pinning down because they are the promise made to someone
 * backing up a phone on a slow connection, and the orientation table because it
 * is the difference between a photograph and the same photograph lying on its
 * side.
 */
class ImagePolicyTest {

    @Test
    fun `the limit is a longest edge of two thousand and forty eight pixels`() {
        assertEquals(2048, ImagePolicy.MAX_EDGE)
        assertFalse(ImagePolicy.needsResize(2048, 1536))
        assertFalse(ImagePolicy.needsResize(1536, 2048))
        assertTrue(ImagePolicy.needsResize(2049, 1536))
        assertTrue(ImagePolicy.needsResize(1536, 2049))
    }

    @Test
    fun `a photo inside the limit is left at its own size`() {
        // Scaling up to a round number would make a small photo bigger, blurrier
        // and heavier at the same time.
        assertEquals(1000 to 800, ImagePolicy.targetSize(1000, 800))
        assertEquals(2048 to 2048, ImagePolicy.targetSize(2048, 2048))
    }

    @Test
    fun `a landscape photo is scaled on its long edge`() {
        assertEquals(2048 to 1365, ImagePolicy.targetSize(3000, 2000))
        assertEquals(2048 to 1536, ImagePolicy.targetSize(4096, 3072))
    }

    @Test
    fun `a portrait photo is scaled on its long edge`() {
        assertEquals(1536 to 2048, ImagePolicy.targetSize(3000, 4000))
    }

    @Test
    fun `a panorama keeps its shape and never collapses to nothing`() {
        assertEquals(2048 to 26, ImagePolicy.targetSize(8000, 100))
        // Two pixels tall against ten thousand wide: the short edge rounds to one
        // rather than to zero, because an image with no pixels is not an image.
        assertEquals(2048 to 1, ImagePolicy.targetSize(10000, 2))
    }

    @Test
    fun `decoding is sampled so a large photo is never held at full size`() {
        // A power of two, and never below the limit: the exact fit is done after
        // decoding, so this step must not throw away pixels that were wanted.
        assertEquals(1, ImagePolicy.sampleSize(2048, 1536))
        assertEquals(1, ImagePolicy.sampleSize(3000, 2000))
        assertEquals(2, ImagePolicy.sampleSize(8000, 6000))
        assertEquals(4, ImagePolicy.sampleSize(12000, 9000))

        for (width in listOf(2048, 3000, 4000, 4096, 8000, 12000, 40000)) {
            val sample = ImagePolicy.sampleSize(width, width)
            assertTrue("$sample is not a power of two", sample > 0 && sample and (sample - 1) == 0)
            assertTrue("$width sampled by $sample falls under the limit", width / sample >= 2048)
        }
    }

    @Test
    fun `a sampling factor is always returned even for a nonsensical limit`() {
        assertEquals(1, ImagePolicy.sampleSize(4000, 3000, maxEdge = 0))
        assertEquals(1, ImagePolicy.sampleSize(4000, 3000, maxEdge = -5))
    }

    @Test
    fun `the eight stored orientations map onto turns and mirrors`() {
        val table = mapOf(
            1 to Triple(0, false, false),
            2 to Triple(0, true, false),
            3 to Triple(180, false, false),
            4 to Triple(0, false, true),
            5 to Triple(90, true, false),
            6 to Triple(90, false, false),
            7 to Triple(270, true, false),
            8 to Triple(270, false, false),
        )

        for ((value, expected) in table) {
            val orientation = ImagePolicy.orientation(value)
            assertEquals("degrees for $value", expected.first, orientation.degrees)
            assertEquals("horizontal mirror for $value", expected.second, orientation.flipX)
            assertEquals("vertical mirror for $value", expected.third, orientation.flipY)
        }
    }

    @Test
    fun `the upright value moves nothing and no value is invented for the rest`() {
        assertFalse(ImagePolicy.orientation(1).movesPixels)
        // 0 and 9 are not orientations; treating them as a rotation would turn a
        // photograph for no reason at all.
        assertFalse(ImagePolicy.orientation(0).movesPixels)
        assertFalse(ImagePolicy.orientation(9).movesPixels)
        assertTrue(ImagePolicy.orientation(5).movesPixels)
    }
}
