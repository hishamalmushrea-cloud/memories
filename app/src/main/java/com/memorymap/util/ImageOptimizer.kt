package com.memorymap.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.core.graphics.scale
import com.memorymap.domain.model.MediaType
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bytes to put in the bucket, and the extension that names them.
 *
 * The extension is not decoration: the object key is built from it, and the key
 * is what every other device derives its own file name from. It has to describe
 * the bytes that actually arrive, which is why an image rewritten into JPEG says
 * `jpg` even when the local file was called something else.
 */
class UploadBytes(val bytes: ByteArray, val extension: String)

/**
 * Turns a photo into the version that should leave the device.
 *
 * A port rather than a call to the decoder, so the sync layer can be tested
 * without one: what an upload has to prove is that it sends what it was handed,
 * and that can be checked with a stand-in. The real work - shrinking, turning
 * and re-encoding - is one file's worth of Android and is tested on its own.
 *
 * Implementations never throw and never return nothing. A photo that cannot be
 * prepared is uploaded as it is, because a file the user asked to back up is
 * always better off in the cloud than missing from it.
 */
interface ImageOptimizer {

    /**
     * The bytes for [path], which currently holds [bytes].
     *
     * [fallbackExtension] is the extension to keep when the bytes are passed
     * through unchanged.
     */
    fun prepare(
        path: String,
        type: MediaType,
        bytes: ByteArray,
        fallbackExtension: String,
    ): UploadBytes
}

/**
 * The real thing: decode, turn and scale, then write JPEG.
 *
 * Three rules keep this from doing harm:
 *
 *  - only JPEG is touched. A PNG may be a screenshot with transparency, a GIF
 *    may be moving and an HEIC may not decode at all on the oldest supported
 *    phone, so an image this cannot rewrite faithfully is left exactly as it is.
 *  - a rewritten photo is only used when it came out smaller than the original,
 *    so "compression" can never cost the user more bytes than it saves.
 *  - a photo whose pixels have to be turned is never parted from its own header
 *    unless the pixels were turned. The Exif block is the only record of which
 *    way up an unrotated photo goes, and throwing it away would put the photo on
 *    its side in the cloud for good.
 *
 * What the upload loses either way is the metadata: when the pixels are upright
 * the Exif, Photoshop and comment segments are removed without re-encoding, and
 * when the photo is rewritten the encoder simply never writes them. The local
 * file keeps everything - only the copy that leaves the device is stripped - so
 * the location, the camera and the timestamp are still on the phone they came
 * from.
 */
@Singleton
class AndroidImageOptimizer @Inject constructor() : ImageOptimizer {

    override fun prepare(
        path: String,
        type: MediaType,
        bytes: ByteArray,
        fallbackExtension: String,
    ): UploadBytes {
        val untouched = UploadBytes(bytes, fallbackExtension)
        if (type != MediaType.PHOTO) return untouched
        val header = JpegMetadata.read(bytes) ?: return untouched
        // An Exif block that will not say which way up the pixels go: leaving the
        // file completely alone is the only answer that cannot be wrong.
        val orientation = header.orientation ?: return untouched
        val orientationPolicy = ImagePolicy.orientation(orientation)
        val resize = header.width != null && header.height != null &&
            ImagePolicy.needsResize(header.width, header.height)

        if (orientationPolicy.movesPixels || resize) {
            val rewritten = runCatching {
                reencode(path, orientationPolicy, header.width, header.height)
            }.getOrNull()
            if (rewritten != null && rewritten.size in 1 until bytes.size) {
                return UploadBytes(rewritten, JPEG_EXTENSION)
            }
            // Nothing was rewritten, and the pixels still have to be turned, so
            // the file has to keep the header that says so.
            if (orientationPolicy.movesPixels) return untouched
        }

        // The pixels are upright and already small enough. They are copied over
        // byte for byte and only the metadata around them goes.
        val stripped = if (header.metadata) JpegMetadata.stripMetadata(bytes) else null
        if (stripped != null && stripped.size in 1 until bytes.size) {
            return UploadBytes(stripped, JPEG_EXTENSION)
        }
        return untouched
    }

    /**
     * Decodes, turns and scales [path], and returns the JPEG it wrote.
     *
     * The decoded frames are recycled as soon as their successor exists: a
     * twelve megapixel photo is forty-eight megabytes of pixels, and holding two
     * of those at once is how an upload turns into an out-of-memory kill on a
     * cheap phone.
     */
    private fun reencode(
        path: String,
        orientation: ImagePolicy.Orientation,
        width: Int?,
        height: Int?,
    ): ByteArray? {
        var frame = decode(path, width, height) ?: return null
        try {
            if (width != null && height != null) {
                val target = ImagePolicy.targetSize(width, height)
                if (frame.width > target.first || frame.height > target.second) {
                    // The KTX wrapper over createScaledBitmap: it keeps the same
                    // habit of handing back the instance it was given when the
                    // size already matches, which is what the check below is for.
                    val scaled = frame.scale(target.first, target.second, true)
                    if (scaled !== frame) frame.recycle()
                    frame = scaled
                }
            }
            val turn = Matrix()
            turn.setRotate(orientation.degrees.toFloat())
            if (orientation.flipX || orientation.flipY) {
                turn.postScale(
                    if (orientation.flipX) -1f else 1f,
                    if (orientation.flipY) -1f else 1f,
                )
            }
            val upright = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, turn, true)
            if (upright !== frame) frame.recycle()
            frame = upright

            val output = ByteArrayOutputStream()
            return if (frame.compress(Bitmap.CompressFormat.JPEG, ImagePolicy.QUALITY, output)) {
                output.toByteArray()
            } else {
                null
            }
        } finally {
            frame.recycle()
        }
    }

    /**
     * The picture at [path], sampled so it never lands at full size in memory.
     *
     * The size comes from the header rather than from a second pass over the
     * file, which is what lets the sampling factor be chosen before anything is
     * allocated.
     */
    private fun decode(path: String, width: Int?, height: Int?): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            if (width != null && height != null) inSampleSize = ImagePolicy.sampleSize(width, height)
        }
        BitmapFactory.decodeFile(path, options)
    }.getOrNull()

    private companion object {
        /** What a JPEG is called once it has been through the encoder. */
        const val JPEG_EXTENSION = "jpg"
    }
}
