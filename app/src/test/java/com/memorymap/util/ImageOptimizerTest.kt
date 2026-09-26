package com.memorymap.util

import com.memorymap.domain.model.MediaType
import com.memorymap.testing.JpegFixtures
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the optimizer decides when it is not allowed to be wrong.
 *
 * These are the cases where the right answer is to do nothing: a file that is
 * not a photograph, a photograph that is not a JPEG, a photograph whose pixels
 * cannot be read but which still has to be turned. Every one of them ends with
 * the user's own bytes in the cloud, because a backup that silently drops a file
 * is worse than a backup that uploads it at full size.
 *
 * The pixel work lives in [ImageOptimizerTransformTest], which needs a codec.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageOptimizerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val optimizer = AndroidImageOptimizer()

    private val pngHeader = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D,
    )

    @Test
    fun `a photo that is not a JPEG is handed back untouched`() {
        // A PNG may be a screenshot with transparency, a GIF may be moving and an
        // HEIC may not decode on the oldest phone this app supports. None of them
        // can be rewritten faithfully, so none of them is touched.
        val prepared = optimizer.prepare(
            path = missingFile().absolutePath,
            type = MediaType.PHOTO,
            bytes = pngHeader,
            fallbackExtension = "png",
        )

        assertEquals(pngHeader.toList(), prepared.bytes.toList())
        assertEquals("png", prepared.extension)
    }

    @Test
    fun `a file that is not a photo is never rewritten`() {
        // Even bytes that are a perfect JPEG, which a video's are not: the kind of
        // attachment decides this before anything is read.
        val jpeg = JpegFixtures.jpeg(JpegFixtures.exif(6), JpegFixtures.frame(4000, 3000), JpegFixtures.scan())

        for (type in listOf(MediaType.VIDEO, MediaType.AUDIO)) {
            val prepared = optimizer.prepare(
                path = missingFile().absolutePath,
                type = type,
                bytes = jpeg,
                fallbackExtension = "mp4",
            )
            assertEquals(type.name, jpeg.toList(), prepared.bytes.toList())
            assertEquals(type.name, "mp4", prepared.extension)
        }
    }

    @Test
    fun `an upright photo with nothing to drop is copied without being rewritten`() {
        val file = JpegFixtures.jpeg(
            JpegFixtures.jfif(),
            JpegFixtures.frame(width = 640, height = 480),
            JpegFixtures.scan(),
        )

        val prepared = optimizer.prepare(
            path = missingFile().absolutePath,
            type = MediaType.PHOTO,
            bytes = file,
            fallbackExtension = "jpeg",
        )

        assertEquals(file.toList(), prepared.bytes.toList())
        assertEquals("jpeg", prepared.extension)
    }

    @Test
    fun `the metadata goes and the picture stays on a photo too small to resize`() {
        val file = JpegFixtures.jpeg(
            JpegFixtures.jfif(),
            JpegFixtures.exif(1),
            JpegFixtures.comment("taken at home"),
            JpegFixtures.frame(width = 640, height = 480),
            JpegFixtures.scan(),
        )
        val path = File(temporaryFolder.root, "photo.jpeg")
        path.writeBytes(file)

        val prepared = optimizer.prepare(
            path = path.absolutePath,
            type = MediaType.PHOTO,
            bytes = file,
            fallbackExtension = "jpeg",
        )

        assertTrue("the upload has to be smaller", prepared.bytes.size < file.size)
        val header = JpegMetadata.read(prepared.bytes)!!
        assertFalse("the metadata has to be gone", header.metadata)
        assertEquals(640, header.width)
        assertEquals(480, header.height)
        // Read as one-byte characters so the search cannot miss a byte sequence
        // by re-encoding it.
        val text = String(prepared.bytes, Charsets.ISO_8859_1)
        assertFalse("the comment travelled", text.contains("taken at home"))
        // The extension describes the bytes, not the file's name, because the
        // object key is built from it and every other device copies it.
        assertEquals("jpg", prepared.extension)
        // The file on this device is what the user keeps. Reading it is all that
        // happened to it, and this photo was never decoded at all.
        assertEquals(file.toList(), path.readBytes().toList())
    }

    @Test
    fun `a photo whose orientation cannot be trusted is left completely alone`() {
        // The file has an Exif block saying "nine", which is not an orientation.
        // Removing the block would take away the only record of which way up the
        // picture goes, so nothing at all is touched.
        val file = JpegFixtures.jpeg(
            JpegFixtures.exif(9),
            JpegFixtures.frame(width = 640, height = 480),
            JpegFixtures.scan(),
        )

        val prepared = optimizer.prepare(
            path = missingFile().absolutePath,
            type = MediaType.PHOTO,
            bytes = file,
            fallbackExtension = "jpg",
        )

        assertEquals(file.toList(), prepared.bytes.toList())
    }

    @Test
    fun `a photo that has to be turned keeps its header when the pixels are unreadable`() {
        // A rotated photo is only safe to strip if the pixels were turned first,
        // and they can only be turned by decoding them. This file cannot be
        // decoded, so the Exif block is the picture's only chance of arriving the
        // right way up and it is left where it is.
        val file = JpegFixtures.jpeg(
            JpegFixtures.exif(6),
            JpegFixtures.frame(width = 640, height = 480),
            JpegFixtures.scan(),
        )

        val prepared = optimizer.prepare(
            path = missingFile().absolutePath,
            type = MediaType.PHOTO,
            bytes = file,
            fallbackExtension = "jpeg",
        )

        assertEquals(file.toList(), prepared.bytes.toList())
        assertEquals("jpeg", prepared.extension)
    }

    @Test
    fun `a file that only looks like a JPEG is not read as one`() {
        // Two bytes of start of image and then nonsense: enough to fool a check
        // that stopped at the magic number, not enough to be a photograph.
        val file = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33, 0x44)

        val prepared = optimizer.prepare(
            path = missingFile().absolutePath,
            type = MediaType.PHOTO,
            bytes = file,
            fallbackExtension = "jpg",
        )

        assertEquals(file.toList(), prepared.bytes.toList())
    }

    private fun missingFile(): File = File(temporaryFolder.root, "not-here.jpg")
}
