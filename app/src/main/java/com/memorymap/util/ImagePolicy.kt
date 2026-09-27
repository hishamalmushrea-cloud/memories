package com.memorymap.util

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The rules for what an uploaded photo is allowed to become.
 *
 * Separated from the code that decodes and re-encodes so the numbers can be
 * argued with and tested without a device: how large an upload may be, how the
 * resize keeps the shape of the picture, and what each Exif orientation means in
 * terms of turning and mirroring.
 *
 * The orientation table is the part worth reading twice. The stored value is a
 * promise about how the pixels should be displayed, and the only safe way to
 * drop it is to make the pixels match it first. Values 2, 4, 5 and 7 do not just
 * turn a photo, they mirror it, and mirroring the wrong way is worse than not
 * shrinking the file at all.
 */
object ImagePolicy {

    /** The longest edge an uploaded photo keeps. */
    const val MAX_EDGE = 2048

    /** Quality for a rewritten photo: a loss nobody sees at this size. */
    const val QUALITY = 82

    /** How a stored orientation maps onto turning and mirroring the pixels. */
    class Orientation(val degrees: Int, val flipX: Boolean, val flipY: Boolean) {

        /** True when the pixels have to be moved for the value to be honoured. */
        val movesPixels: Boolean get() = degrees != 0 || flipX || flipY
    }

    private val UPRIGHT = Orientation(degrees = 0, flipX = false, flipY = false)

    /**
     * What the stored [orientation] asks for.
     *
     * The turn is applied first and the mirror in the frame the turn produced,
     * which is what makes five and seven come out as the transpositions they are
     * rather than as ordinary rotations. One and anything unrecognised mean the
     * pixels already agree with the file, so nothing is moved.
     */
    fun orientation(orientation: Int): Orientation = when (orientation) {
        2 -> Orientation(degrees = 0, flipX = true, flipY = false)
        3 -> Orientation(degrees = 180, flipX = false, flipY = false)
        4 -> Orientation(degrees = 0, flipX = false, flipY = true)
        5 -> Orientation(degrees = 90, flipX = true, flipY = false)
        6 -> Orientation(degrees = 90, flipX = false, flipY = false)
        7 -> Orientation(degrees = 270, flipX = true, flipY = false)
        8 -> Orientation(degrees = 270, flipX = false, flipY = false)
        else -> UPRIGHT
    }

    /** True when a photo of this size is larger than an upload should be. */
    fun needsResize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Boolean =
        max(width, height) > maxEdge

    /**
     * The size to scale [width] by [height] down to, keeping its shape.
     *
     * Never enlarges: a photo already inside the limit comes back unchanged, so a
     * small picture is never made bigger and blurrier by being "optimised".
     */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Pair<Int, Int> {
        val longest = max(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /**
     * The power of two to decode at, so a large photo is never held at full size.
     *
     * Decoding is what costs memory: a twelve megapixel photo is forty-eight
     * megabytes as raw pixels, and the whole point of this step is that the
     * device never has to find them. The chosen factor keeps the decoded picture
     * at or above the target, which leaves [targetSize] to do the exact fit and
     * means this never throws pixels away that were wanted.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        if (maxEdge <= 0) return 1
        var sample = 1
        while (sample < MAX_SAMPLE && max(width, height) / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    private const val MAX_SAMPLE = 4096
}
