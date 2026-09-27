package com.memorymap.testing

import java.io.ByteArrayOutputStream

/**
 * JPEG files, assembled byte by byte.
 *
 * The reader under test is the thing that decides what may be thrown out of a
 * user's photograph, so the tests around it are about structures the platform
 * would never produce on demand: an Exif block in big-endian order, one whose
 * directory runs off the end of its segment, a comment segment nobody writes any
 * more. Building them here keeps each test about one rule instead of one file.
 */
object JpegFixtures {

    const val SOI = 0xD8
    const val EOI = 0xD9
    const val APP0 = 0xE0
    const val APP1 = 0xE1
    const val APP13 = 0xED
    const val COM = 0xFE
    const val SOF0 = 0xC0
    const val SOS = 0xDA
    const val ORIENTATION_TAG = 0x0112

    /** Start of image, then [parts], then end of image. */
    fun jpeg(vararg parts: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(bareMarker(SOI))
        parts.forEach { output.write(it) }
        output.write(bareMarker(EOI))
        return output.toByteArray()
    }

    /** A marker that carries no payload, like the start of image. */
    fun bareMarker(code: Int): ByteArray = byteArrayOf(0xFF.toByte(), code.toByte())

    /** A marker segment: the marker, its length, then the payload. */
    fun segment(code: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(),
            code.toByte(),
            (length shr 8).toByte(),
            length.toByte(),
        ) + payload
    }

    /** The JFIF segment most encoders open with. */
    fun jfif(): ByteArray = segment(
        APP0,
        byteArrayOf(0x4A, 0x46, 0x49, 0x46, 0x00, 1, 1, 0, 0, 1, 0, 1, 0, 0),
    )

    fun comment(text: String): ByteArray = segment(COM, text.toByteArray(Charsets.US_ASCII))

    /** A frame header: the only place the pixel size is written down. */
    fun frame(width: Int, height: Int): ByteArray = segment(
        SOF0,
        byteArrayOf(
            8,
            (height shr 8).toByte(),
            height.toByte(),
            (width shr 8).toByte(),
            width.toByte(),
            1,
            1,
            0x11,
            0,
        ),
    )

    /**
     * A start of scan and some entropy data.
     *
     * The default [entropy] contains a byte that looks like the start of a marker
     * segment, escaped the way JPEG escapes it. Anything that walks the whole
     * file instead of stopping at the scan reads the picture as a header.
     */
    fun scan(entropy: ByteArray = byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0x00, 0x0A)): ByteArray =
        segment(SOS, byteArrayOf(1, 1, 0x00, 0, 63, 0)) + entropy

    /**
     * An Exif block holding [orientation], or holding nothing when it is null.
     *
     * The TIFF header is written in either byte order because cameras use both,
     * and a reader that only understands one of them turns half the world's
     * portrait photos on their side.
     */
    fun exif(orientation: Int?, littleEndian: Boolean = true): ByteArray {
        val entries = if (orientation == null) 0 else 1
        val tiff = ByteArray(8 + 2 + entries * 12 + 4)
        if (littleEndian) {
            tiff[0] = 'I'.code.toByte()
            tiff[1] = 'I'.code.toByte()
        } else {
            tiff[0] = 'M'.code.toByte()
            tiff[1] = 'M'.code.toByte()
        }
        put16(tiff, 2, 42, littleEndian)
        put32(tiff, 4, 8, littleEndian)
        put16(tiff, 8, entries, littleEndian)
        if (entries == 1) {
            put16(tiff, 10, ORIENTATION_TAG, littleEndian)
            put16(tiff, 12, 3, littleEndian)
            put32(tiff, 14, 1, littleEndian)
            put16(tiff, 18, orientation ?: 1, littleEndian)
        }
        put32(tiff, 8 + 2 + entries * 12, 0, littleEndian)
        return segment(APP1, EXIF_HEADING + tiff)
    }

    /** An APP1 segment that claims to be Exif but has no valid TIFF header. */
    fun brokenExif(): ByteArray = segment(APP1, EXIF_HEADING + byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

    /** [bytes] with [part] pushed in directly after the start of image. */
    fun injectAfterStartOfImage(bytes: ByteArray, part: ByteArray): ByteArray {
        require(bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == SOI.toByte()) {
            "not a JPEG"
        }
        return bytes.copyOfRange(0, 2) + part + bytes.copyOfRange(2, bytes.size)
    }

    private val EXIF_HEADING = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)

    private fun put16(target: ByteArray, at: Int, value: Int, littleEndian: Boolean) {
        val high = (value shr 8).toByte()
        val low = value.toByte()
        target[at] = if (littleEndian) low else high
        target[at + 1] = if (littleEndian) high else low
    }

    private fun put32(target: ByteArray, at: Int, value: Int, littleEndian: Boolean) {
        for (index in 0 until 4) {
            val shift = if (littleEndian) index * 8 else (3 - index) * 8
            target[at + index] = (value shr shift).toByte()
        }
    }
}
