package com.memorymap.data.remote

/**
 * The bucket an attachment's bytes live in.
 *
 * A port rather than a direct Supabase call, for the same reason the records
 * tables have one: the upload path is the part of this feature that cannot be
 * exercised without a project, so everything that decides *what* to upload has
 * to be separable from the call that uploads it.
 *
 * Every method reports failure instead of throwing. A run that cannot reach the
 * bucket has to leave the attachment visibly queued, not crash the worker.
 */
interface MediaStorage {

    /** Writes [bytes] at [objectKey], replacing whatever was there. */
    suspend fun upload(objectKey: String, bytes: ByteArray): Boolean

    /** Reads the bytes at [objectKey], or null when it could not be read. */
    suspend fun download(objectKey: String): ByteArray?

    /** Removes the object at [objectKey]. */
    suspend fun remove(objectKey: String): Boolean
}

/**
 * Where one attachment sits inside the bucket.
 *
 * The first folder is the user id, and that is not a convention: the storage
 * policies in `supabase/schema.sql` decide access with
 * `(storage.foldername(name))[1] = auth.uid()::text`, so an object stored
 * anywhere else belongs to nobody and is refused. Both halves of this rule are
 * tested - here for the shape of the key, and in `SchemaSecurityTest` for the
 * policy that requires it.
 *
 * The attachment's id is the file name, so the same attachment always lands on
 * the same key on every device and a retry after a failed run replaces its own
 * object instead of leaving a second copy beside it.
 */
object MediaObjectKey {

    fun of(userId: String, mediaId: String, extension: String): String =
        "$userId/$mediaId.${extensionOf(extension)}"

    /**
     * The extension to keep for a file at [localPath], or `bin` when it has none.
     *
     * The name carries the type: the object is stored without a content type of
     * its own, and both the bucket and any later reader infer it from this.
     */
    fun extensionOf(localPath: String): String =
        localPath.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it.length <= MAX_EXTENSION && it.none(Char::isWhitespace) }
            ?.lowercase()
            ?: "bin"

    /** Long enough for `jpeg` and `m4a`, short enough to reject a whole path. */
    private const val MAX_EXTENSION = 8
}
