package com.memorymap.util

import java.io.ByteArrayOutputStream

/**
 * What a JPEG says about itself, read straight from its bytes.
 *
 * Reading the header here rather than asking the platform buys three things this
 * app needs. It runs in a unit test with no device, so the rules about what may
 * be thrown away are checked instead of assumed. It reports what the file
 * actually is, whatever its name or MIME type claims. And it never decodes the
 * pixels: a twelve megapixel photo is inspected by looking at a few dozen bytes.
 *
 * Only the parts the upload path needs are understood - the stored orientation,
 * the pixel size, and which segments are metadata rather than picture.
 *
 * Nothing here accepts a rotated-and-mirrored file without proof: when a file
 * carries an Exif block whose orientation cannot be read, [Header.orientation] is
 * null and the caller is expected to leave the file alone. Guessing would turn a
 * portrait photo on its side.
 */
object JpegMetadata {

    /** The orientation that means the pixels are already the right way up. */
    const val ORIENTATION_NORMAL = 1

    /**
     * What one JPEG's header holds.
     *
     * [width] and [height] are null when the file has no frame header, which
     * makes it a stream this app has no business rewriting. [metadata] is true
     * when there is a segment [stripMetadata] would drop, so the common case of a
     * file with nothing to remove costs no copy. [orientation] is 1 when the
     * pixels are upright, the stored value when there is one, and null when an
     * Exif block is present but its orientation cannot be trusted.
     */
    class Header(
        val width: Int?,
        val height: Int?,
        val metadata: Boolean,
        val orientation: Int?,
    )

    /** True when [bytes] start with the JPEG start-of-image marker. */
    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == MARKER.toByte() &&
            bytes[1] == SOI.toByte()

    /**
     * Reads the header of [bytes], or null when this is not a JPEG at all.
     *
     * A file that is a JPEG but whose header is malformed also comes back null:
     * there is no partial answer worth acting on, because every action here
     * rewrites the file.
     */
    fun read(bytes: ByteArray): Header? {
        val layout = scan(bytes) ?: return null
        val frame = layout.segments.firstOrNull { isFrameHeader(it.marker) }
        val exif = layout.segments.firstOrNull { it.marker == APP1 && it.hasExifHeading(bytes) }
        return Header(
            width = frame?.payloadU16(bytes, 3),
            height = frame?.payloadU16(bytes, 1),
            metadata = layout.segments.any { isMetadata(it.marker) },
            orientation = if (exif == null) ORIENTATION_NORMAL else exif.orientation(bytes),
        )
    }

    /**
     * The same picture with the metadata segments removed, or null when there is
     * nothing to remove.
     *
     * What goes: APP1 (Exif and XMP), APP13 (Photoshop and IPTC) and comment
     * segments. What stays: everything the picture is made of, including the
     * colour profile in APP2, because dropping that would change how the photo
     * looks. The scan itself is copied over untouched, so no pixel can move.
     *
     * A file with no scan is refused rather than rebuilt: there is no picture to
     * keep, and rewriting a corrupt file would only make it differently corrupt.
     *
     * The caller is responsible for having checked the orientation first. On a
     * file whose pixels are not upright, the Exif block is the only record of
     * which way up they go, and removing it would leave the photo on its side.
     */
    fun stripMetadata(bytes: ByteArray): ByteArray? {
        val layout = scan(bytes) ?: return null
        if (!layout.hasScan) return null
        if (layout.segments.none { isMetadata(it.marker) }) return null

        val output = ByteArrayOutputStream(bytes.size)
        output.write(bytes, 0, 2)
        for (segment in layout.segments) {
            if (isMetadata(segment.marker)) continue
            output.write(bytes, segment.start, segment.length + 2)
        }
        output.write(bytes, layout.scanStart, bytes.size - layout.scanStart)
        return output.toByteArray()
    }
}

/** One marker segment: which marker, where it starts, how long its payload is. */
private class Segment(val marker: Int, val start: Int, val length: Int) {

    /** The first byte of this segment's payload. */
    val payload: Int get() = start + 4

    /** Payload bytes, not counting the two length bytes. */
    val payloadLength: Int get() = length - 2

    fun hasExifHeading(bytes: ByteArray): Boolean =
        payloadLength >= EXIF_HEADING.size &&
            EXIF_HEADING.indices.all { bytes[payload + it] == EXIF_HEADING[it] }

    /** The pixel height or width from a frame header: 1 is height, 3 is width. */
    fun payloadU16(bytes: ByteArray, offset: Int): Int? {
        if (payloadLength < offset + 2) return null
        return u16(bytes, payload + offset).takeIf { it > 0 }
    }

    /**
     * The Exif orientation, or null when the block does not yield a usable one.
     *
     * The bounds are the whole story here. The segment was checked to fit inside
     * the file before it was built, so a length of at least fourteen payload
     * bytes means the eight bytes of TIFF header are really there; everything
     * after that is read through helpers that answer -1 rather than throwing.
     */
    fun orientation(bytes: ByteArray): Int? {
        val tiff = payload + EXIF_HEADING.size
        if (payloadLength < EXIF_HEADING.size + 8) return null
        val order = when {
            bytes[tiff] == 'I'.code.toByte() && bytes[tiff + 1] == 'I'.code.toByte() -> ByteOrder.LITTLE
            bytes[tiff] == 'M'.code.toByte() && bytes[tiff + 1] == 'M'.code.toByte() -> ByteOrder.BIG
            else -> return null
        }
        if (order.u16(bytes, tiff + 2) != 42) return null
        val offset = order.u32(bytes, tiff + 4)
        if (offset < 0 || tiff + offset + 2 > bytes.size) return null
        val directory = tiff + offset
        val entries = order.u16(bytes, directory)
        // A directory this long is not a photograph's; refuse rather than walk
        // off the end of the segment looking for a tag that is not there.
        if (entries < 0 || entries > MAX_ENTRIES) return null
        for (index in 0 until entries) {
            val entry = directory + 2 + index * ENTRY_SIZE
            if (entry + ENTRY_SIZE > bytes.size) return null
            if (order.u16(bytes, entry) != ORIENTATION_TAG) continue
            // Short, one value: the number sits in the first two bytes of the
            // value field in either byte order, and is never an offset.
            if (order.u16(bytes, entry + 2) != TYPE_SHORT) return null
            if (order.u32(bytes, entry + 4) != 1) return null
            return order.u16(bytes, entry + 8).takeIf { it in 1..8 }
        }
        // No orientation tag at all: the file is not claiming to be rotated.
        return JpegMetadata.ORIENTATION_NORMAL
    }
}

/**
 * The marker segments before the scan, where the scan begins, and whether there
 * is a scan at all: a file that runs from its header straight to an end marker
 * is a header with no picture behind it.
 */
private class Layout(val segments: List<Segment>, val scanStart: Int, val hasScan: Boolean)

/** TIFF is written in either byte order, and both are in the wild. */
private enum class ByteOrder {
    LITTLE,
    BIG;

    fun u16(bytes: ByteArray, at: Int): Int =
        if (at < 0 || at + 2 > bytes.size) {
            -1
        } else if (this == LITTLE) {
            (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        } else {
            ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
        }

    fun u32(bytes: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > bytes.size) return -1
        var value = 0
        for (index in 0 until 4) {
            val shift = if (this == LITTLE) index * 8 else (3 - index) * 8
            value = value or ((bytes[at + index].toInt() and 0xFF) shl shift)
        }
        return value
    }
}

/**
 * Walks the marker segments up to the first start of scan.
 *
 * Stops there rather than walking the whole file on purpose: after that marker
 * come entropy-coded bytes, where a byte that happens to look like a marker is
 * escaped with a zero, and a walker that did not know that would read the
 * picture as a header.
 *
 * Returns null for anything malformed, since every caller here rewrites the file
 * it was given and a length that runs past the end would mean inventing bytes
 * that were never there.
 */
private fun scan(bytes: ByteArray): Layout? {
    if (!JpegMetadata.isJpeg(bytes)) return null
    val segments = ArrayList<Segment>()
    var offset = 2
    while (offset + 1 < bytes.size) {
        if (bytes[offset] != MARKER.toByte()) return null
        val marker = bytes[offset + 1].toInt() and 0xFF
        when {
            // A fill byte: markers may be preceded by any number of them.
            marker == MARKER -> offset++
            marker == SOS -> return Layout(segments, offset, hasScan = true)
            marker == EOI -> return Layout(segments, offset, hasScan = false)
            marker == TEM || marker in 0xD0..0xD7 -> offset += 2
            else -> {
                if (offset + 4 > bytes.size) return null
                val length = u16(bytes, offset + 2)
                if (length < 2 || offset + 2 + length > bytes.size) return null
                segments += Segment(marker, offset, length)
                offset += 2 + length
            }
        }
    }
    return null
}

/** Big-endian, which is what a marker segment's length field always is. */
private fun u16(bytes: ByteArray, at: Int): Int =
    ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

/** Frame headers carry the size; the other markers in that range do not. */
private fun isFrameHeader(marker: Int): Boolean =
    marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC

/** The segments that describe the file rather than the picture. */
private fun isMetadata(marker: Int): Boolean =
    marker == APP1 || marker == APP13 || marker == COM

private const val MARKER = 0xFF
private const val TEM = 0x01
private const val SOI = 0xD8
private const val EOI = 0xD9
private const val SOS = 0xDA
private const val APP1 = 0xE1
private const val APP13 = 0xED
private const val COM = 0xFE
private const val ORIENTATION_TAG = 0x0112
private const val TYPE_SHORT = 3
private const val ENTRY_SIZE = 12
private const val MAX_ENTRIES = 512

/** The six bytes that introduce an Exif block inside an APP1 segment. */
private val EXIF_HEADING = byteArrayOf(0x45, 0x78, 0x69, 0x66, 0x00, 0x00)
