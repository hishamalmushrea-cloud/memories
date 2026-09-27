package com.memorymap.util

/**
 * The largest single file this project accepts in the cloud.
 *
 * Supabase refuses an object larger than the project's global file size limit,
 * and the free plan's limit is 50 MB; the paid plans raise it. The app cannot ask
 * the server what the number is, so it is a build setting - the same shape of
 * decision as the tile server, and configurable for the same reason: it depends
 * on whose project this build points at and what they are paying for.
 *
 * It exists at all because of what its absence looked like from the outside. A
 * file over the cap was uploaded, refused, retried on every sync, and reported as
 * a failure with no reason the person could act on: the video was there, the
 * cloud button was on, and nothing ever arrived. See `docs/SERVICE_LIMITS.md`.
 *
 * The check is on the bytes that would actually be sent, not on the file on
 * disk: a photo is prepared (shrunk to [ImagePolicy.MAX_EDGE] and stripped of its
 * metadata) on the way out, so a large original can still travel.
 */
/** A plain class rather than an inline one: this is injected, and no reason to
 *  make the dependency injection machinery deal with a wrapper it may unwrap. */
class UploadLimit(val bytes: Long) {

    /** True when [size] cannot be sent to this project. */
    fun exceeds(size: Long): Boolean = size > bytes

    /** [size] in whole megabytes, rounded down, for a message a person reads. */
    fun megabytesOf(size: Long): Long = size / BYTES_PER_MEGABYTE

    /** The limit itself in whole megabytes. */
    val megabytes: Long get() = megabytesOf(bytes)

    companion object {
        const val BYTES_PER_MEGABYTE = 1024L * 1024L

        /**
         * What the free plan allows per object, and therefore the default here.
         *
         * It is deliberately not a silent fallback for a missing build setting:
         * `RepositoryModule` passes the value from the build, and this constant is
         * what the tests and the documented default use.
         */
        val FREE_PLAN = UploadLimit(50L * BYTES_PER_MEGABYTE)
    }
}
