package com.memorymap.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.memorymap.domain.model.MediaType
import com.memorymap.testing.JpegFixtures
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pixels, before and after.
 *
 * This is the part of the upload path that can quietly ruin a photograph: a
 * rotation applied the wrong way, a mirror forgotten, a picture left at four
 * thousand pixels wide after being "shrunk". The source is a picture whose four
 * corners are painted four colours, so where each one ends up says exactly what
 * the transform did, and the expectations below are the format's definitions
 * rather than a description of the implementation.
 *
 * Native graphics is switched on because a codec is the subject: the legacy
 * shadows return stand-ins for bitmaps and would let a broken transform pass.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImageOptimizerTransformTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val optimizer = AndroidImageOptimizer()

    @Test
    fun `every stored orientation puts the corners where the format says they go`() {
        // Top left, top right, bottom left, bottom right - the corners of the
        // source, as each stored orientation promises to present them.
        val expectations = mapOf(
            1 to listOf(RED, GREEN, BLUE, YELLOW),
            2 to listOf(GREEN, RED, YELLOW, BLUE),
            3 to listOf(YELLOW, BLUE, GREEN, RED),
            4 to listOf(BLUE, YELLOW, RED, GREEN),
            5 to listOf(RED, BLUE, GREEN, YELLOW),
            6 to listOf(BLUE, RED, YELLOW, GREEN),
            7 to listOf(YELLOW, GREEN, BLUE, RED),
            8 to listOf(GREEN, YELLOW, RED, BLUE),
        )

        for ((orientation, corners) in expectations) {
            val (bytes, path) = source(3000, 2000, orientation)

            val prepared = optimizer.prepare(path.absolutePath, MediaType.PHOTO, bytes, "jpg")

            // The source is written at the highest quality and this size, so a
            // rewrite at eighty two is bound to be smaller. Bytes of the same
            // length would mean the file was handed back untouched, and the
            // corner check below could not tell that apart for the upright case.
            assertTrue(
                "orientation $orientation was not rewritten (${prepared.bytes.size} bytes)",
                prepared.bytes.size < bytes.size,
            )
            val decoded = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)
                ?: error("orientation $orientation did not decode")
            assertCorners(orientation, decoded, corners)
            decoded.recycle()
        }
    }

    @Test
    fun `a photo larger than the limit comes out smaller and inside it`() {
        val (bytes, path) = source(3000, 2000, orientation = null)

        val prepared = optimizer.prepare(path.absolutePath, MediaType.PHOTO, bytes, "jpg")

        assertTrue("${prepared.bytes.size} is not smaller than ${bytes.size}", prepared.bytes.size < bytes.size)
        val decoded = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)!!
        assertEquals(2048, decoded.width)
        assertEquals(1365, decoded.height)
        decoded.recycle()
        // The copy no longer asks to be turned: the pixels were turned instead,
        // which is the only way the header could be dropped safely.
        assertEquals(1, JpegMetadata.read(prepared.bytes)!!.orientation)
    }

    @Test
    fun `a portrait photo is scaled on its long edge too`() {
        val (bytes, path) = source(2000, 3000, orientation = null)

        val prepared = optimizer.prepare(path.absolutePath, MediaType.PHOTO, bytes, "jpg")

        val decoded = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)!!
        assertEquals(1365, decoded.width)
        assertEquals(2048, decoded.height)
        decoded.recycle()
    }

    @Test
    fun `turning and shrinking happen together`() {
        // A portrait photo straight off a phone: the pixels are stored sideways
        // and the header says to turn them. Both steps have to survive each other.
        val (bytes, path) = source(3000, 2000, orientation = 6)

        val prepared = optimizer.prepare(path.absolutePath, MediaType.PHOTO, bytes, "jpg")

        val decoded = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)!!
        assertEquals(1365, decoded.width)
        assertEquals(2048, decoded.height)
        assertCorners(6, decoded, listOf(BLUE, RED, YELLOW, GREEN))
        decoded.recycle()
    }

    @Test
    fun `a photo inside the limit is never resized`() {
        val (bytes, path) = source(600, 400, orientation = null)

        val prepared = optimizer.prepare(path.absolutePath, MediaType.PHOTO, bytes, "jpg")

        assertEquals("jpg", prepared.extension)
        assertTrue("nothing was added", prepared.bytes.size <= bytes.size)
        val decoded = BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)!!
        assertEquals(600, decoded.width)
        assertEquals(400, decoded.height)
        // Every corner is still where it was, so nothing was turned either.
        assertCorners(1, decoded, listOf(RED, GREEN, BLUE, YELLOW))
        decoded.recycle()
    }

    /**
     * Samples the middle of each corner of [decoded] and checks which of the four
     * colours it is nearest to.
     *
     * Nearest rather than equal, because the file has been through a lossy
     * encoder and back: what is being asked is which corner a pixel belongs to,
     * not what the encoder did to it on the way.
     */
    private fun assertCorners(orientation: Int, decoded: Bitmap, expected: List<Int>) {
        val halfWidth = decoded.width / 2
        val halfHeight = decoded.height / 2
        val samples = listOf(
            "top left" to (halfWidth / 2 to halfHeight / 2),
            "top right" to (halfWidth + halfWidth / 2 to halfHeight / 2),
            "bottom left" to (halfWidth / 2 to halfHeight + halfHeight / 2),
            "bottom right" to (halfWidth + halfWidth / 2 to halfHeight + halfHeight / 2),
        )

        samples.forEachIndexed { index, (name, at) ->
            val actual = decoded.getPixel(at.first, at.second)
            assertEquals(
                "orientation $orientation, $name is ${Integer.toHexString(actual)}",
                expected[index],
                nearest(actual),
            )
        }
    }

    private fun nearest(colour: Int): Int = PALETTE.minByOrNull { distance(colour, it) }!!

    private fun distance(first: Int, second: Int): Int {
        val red = ((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)
        val green = ((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)
        val blue = (first and 0xFF) - (second and 0xFF)
        return red * red + green * green + blue * blue
    }

    /** A four corner picture of this size, with [orientation] written into it. */
    private fun source(width: Int, height: Int, orientation: Int?): Pair<ByteArray, File> {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val top = y < height / 2
                val left = x < width / 2
                pixels[y * width + x] = when {
                    top && left -> RED
                    top && !left -> GREEN
                    !top && left -> BLUE
                    else -> YELLOW
                }
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        val output = ByteArrayOutputStream()
        // Written at the highest quality, which is what a camera does, so that a
        // rewrite at eighty two is bound to be the smaller of the two.
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, output))
        bitmap.recycle()

        var bytes = output.toByteArray()
        if (orientation != null) {
            bytes = JpegFixtures.injectAfterStartOfImage(bytes, JpegFixtures.exif(orientation))
        }
        val file = File(temporaryFolder.root, "source-$width-$height-$orientation.jpg")
        file.writeBytes(bytes)
        return bytes to file
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val YELLOW = 0xFFFFFF00.toInt()
        val PALETTE = listOf(RED, GREEN, BLUE, YELLOW)
    }
}
