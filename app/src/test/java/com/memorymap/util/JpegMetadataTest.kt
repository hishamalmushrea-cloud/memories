package com.memorymap.util

import com.memorymap.testing.JpegFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader that decides what may be thrown away before a photo is uploaded.
 *
 * Everything here is about one question: does this file say which way up it is,
 * and can that be trusted? A reader that guesses turns photographs on their
 * side; one that misses metadata leaves the location of a person's home in a
 * bucket. Both are silent, so both are checked here.
 */
class JpegMetadataTest {

    @Test
    fun `anything that does not open like a JPEG is refused`() {
        assertFalse(JpegMetadata.isJpeg(ByteArray(0)))
        assertFalse(JpegMetadata.isJpeg(byteArrayOf(0xFF.toByte())))
        assertFalse(JpegMetadata.isJpeg(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        // A PNG: the second byte is not the start of image.
        assertFalse(JpegMetadata.isJpeg(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))

        assertNull(JpegMetadata.read(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
        assertNull(JpegMetadata.stripMetadata(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)))
    }

    @Test
    fun `the pixel size comes out of the frame header`() {
        val file = JpegFixtures.jpeg(
            JpegFixtures.jfif(),
            JpegFixtures.frame(width = 4000, height = 3000),
            JpegFixtures.scan(),
        )

        val header = JpegMetadata.read(file)!!

        assertEquals(4000, header.width)
        assertEquals(3000, header.height)
    }

    @Test
    fun `a file with no Exif block is upright and has nothing to drop`() {
        val file = JpegFixtures.jpeg(JpegFixtures.jfif(), JpegFixtures.scan())

        val header = JpegMetadata.read(file)!!

        assertEquals(1, header.orientation)
        assertFalse(header.metadata)
        // Nothing to remove, so the file is not copied for the sake of copying.
        assertNull(JpegMetadata.stripMetadata(file))
    }

    @Test
    fun `the stored orientation is read in both byte orders`() {
        val little = JpegFixtures.jpeg(JpegFixtures.exif(6, littleEndian = true), JpegFixtures.scan())
        val big = JpegFixtures.jpeg(JpegFixtures.exif(8, littleEndian = false), JpegFixtures.scan())

        assertEquals(6, JpegMetadata.read(little)!!.orientation)
        assertEquals(8, JpegMetadata.read(big)!!.orientation)
    }

    @Test
    fun `an Exif block with no orientation tag is upright`() {
        // The file is carrying metadata, but it is not claiming to be rotated, so
        // the pixels are what they look like.
        val file = JpegFixtures.jpeg(JpegFixtures.exif(null), JpegFixtures.scan())

        val header = JpegMetadata.read(file)!!

        assertEquals(1, header.orientation)
        assertTrue(header.metadata)
    }

    @Test
    fun `an orientation outside the eight the format defines is not trusted`() {
        val file = JpegFixtures.jpeg(JpegFixtures.exif(9), JpegFixtures.scan())

        val header = JpegMetadata.read(file)!!

        assertTrue(header.metadata)
        assertNull(header.orientation)
    }

    @Test
    fun `an Exif block whose header makes no sense is not trusted`() {
        val file = JpegFixtures.jpeg(JpegFixtures.brokenExif(), JpegFixtures.scan())

        assertNull(JpegMetadata.read(file)!!.orientation)
    }

    @Test
    fun `a literal Exif block reads the way the format says`() {
        // Written out by hand from the specification rather than built by the
        // helper, so the two cannot agree on a mistake: this is an APP1 segment
        // of 34 bytes, an Exif heading, and a little-endian TIFF directory whose
        // single entry is tag 0x0112, type 3, count 1, value 6.
        val file = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22,
            0x45, 0x78, 0x69, 0x66, 0x00, 0x00,
            0x49, 0x49, 0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
            0x01, 0x00,
            0x12, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xD9.toByte(),
        )

        val header = JpegMetadata.read(file)!!

        assertEquals(6, header.orientation)
        assertTrue(header.metadata)
        // No frame header and no scan, so there is no size to report and nothing
        // this reader would rebuild.
        assertNull(header.width)
        assertNull(JpegMetadata.stripMetadata(file))
    }

    @Test
    fun `stripping removes the metadata and leaves the picture byte for byte`() {
        val scan = JpegFixtures.scan()
        val file = JpegFixtures.jpeg(
            JpegFixtures.jfif(),
            JpegFixtures.exif(1),
            JpegFixtures.comment("made on a phone"),
            JpegFixtures.segment(JpegFixtures.APP13, byteArrayOf(1, 2, 3, 4)),
            JpegFixtures.frame(width = 640, height = 480),
            scan,
        )

        val stripped = JpegMetadata.stripMetadata(file)!!

        assertTrue("the copy has to be smaller", stripped.size < file.size)
        val header = JpegMetadata.read(stripped)!!
        assertFalse("no metadata left", header.metadata)
        assertEquals(1, header.orientation)
        assertEquals(640, header.width)
        assertEquals(480, header.height)
        // The scan and everything after it - length, entropy bytes and the end of
        // image marker - are the same bytes that went in. No pixel can have moved.
        assertEquals(
            (scan + JpegFixtures.bareMarker(JpegFixtures.EOI)).toList(),
            stripped.toList().takeLast(scan.size + 2),
        )
        // Neither the size nor the shape of the file was disturbed: it still opens
        // with the same start of image and the same JFIF segment.
        assertEquals(
            (JpegFixtures.bareMarker(JpegFixtures.SOI) + JpegFixtures.jfif()).toList(),
            stripped.toList().take(JpegFixtures.jfif().size + 2),
        )
    }

    @Test
    fun `an escaped marker byte inside the scan is never read as a segment`() {
        // Entropy data ends with a byte that looks like a marker and a zero that
        // escapes it. A walker that carried on past the start of scan would read
        // the picture as a chain of segments and rebuild nonsense.
        val entropy = byteArrayOf(0x33, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00, 0x44)
        val scan = JpegFixtures.scan(entropy)
        val file = JpegFixtures.jpeg(
            JpegFixtures.exif(1),
            JpegFixtures.frame(width = 10, height = 10),
            scan,
        )

        val stripped = JpegMetadata.stripMetadata(file)!!

        assertEquals(
            (scan + JpegFixtures.bareMarker(JpegFixtures.EOI)).toList(),
            stripped.toList().takeLast(scan.size + 2),
        )
    }

    @Test
    fun `a segment claiming to be longer than the file is refused`() {
        // 0xFFFF bytes of payload in a file of a few dozen: every rewrite here
        // copies byte ranges, so a length that runs off the end would mean
        // inventing bytes that were never in the file.
        val lying = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00)
        val file = JpegFixtures.jpeg(lying, JpegFixtures.scan())

        assertNull(JpegMetadata.read(file))
        assertNull(JpegMetadata.stripMetadata(file))
    }

    @Test
    fun `a file that never reaches a scan or an end marker is refused`() {
        val file = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x02)

        assertNull(JpegMetadata.read(file))
        assertNull(JpegMetadata.stripMetadata(file))
    }

    @Test
    fun `padding between segments is allowed`() {
        // Encoders may pad a marker with extra 0xFF bytes; the format says so, and
        // a reader that refused would skip the metadata it is meant to remove.
        val file = JpegFixtures.jpeg(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte()) + JpegFixtures.exif(3),
            JpegFixtures.frame(width = 20, height = 10),
            JpegFixtures.scan(),
        )

        assertEquals(3, JpegMetadata.read(file)!!.orientation)
    }
}
