package com.memorymap.data.remote

import com.memorymap.util.MmLog
import io.github.jan.supabase.storage.storage
import javax.inject.Inject
import javax.inject.Singleton

/** Small enough that a batch's paths stay a reasonable request. */
private const val BATCH_SIZE = 100

/**
 * Supabase Storage, behind [MediaStorage].
 *
 * The bucket is private, so the bytes are only ever read back through an
 * authenticated request; nothing here builds a public URL.
 *
 * The object's content type is deliberately not sent. Supabase infers it from
 * the key's extension, and the keys this app builds always carry one, because
 * that is also what lets a reader make sense of a file whose row it has just
 * received. Sending it as well would be a second source of truth for the same
 * fact, and one that can disagree with the name.
 */
@Singleton
class SupabaseMediaStorage @Inject constructor(
    private val client: SupabaseClientProvider,
) : MediaStorage {

    override suspend fun upload(objectKey: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        return runCatching {
            val bucket = bucket() ?: return false
            // Replacing is the point: a retry after a failed run must not fail
            // because its own previous object is already there.
            bucket.upload(objectKey, bytes) { upsert = true }
            true
        }.getOrElse { error ->
            // The key holds the user's id, so it is never logged.
            MmLog.e("Could not upload an attachment", error)
            false
        }
    }

    override suspend fun download(objectKey: String): ByteArray? = runCatching {
        bucket()?.downloadAuthenticated(objectKey)
    }.getOrElse { error ->
        MmLog.e("Could not download an attachment", error)
        null
    }

    override suspend fun remove(objectKey: String): Boolean = runCatching {
        val bucket = bucket() ?: return false
        bucket.delete(objectKey)
        true
    }.getOrElse { error ->
        MmLog.e("Could not remove an attachment", error)
        false
    }

    /**
     * Removes in batches, because the keys travel in the request.
     *
     * A batch that fails does not stop the rest: the caller is deleting an
     * account and the number that could not be removed is what it reports, so
     * one bad key must not leave the others behind as well.
     */
    override suspend fun removeAll(objectKeys: List<String>): Int {
        if (objectKeys.isEmpty()) return 0
        val bucket = bucket() ?: return 0
        return objectKeys.chunked(BATCH_SIZE).sumOf { batch ->
            runCatching {
                bucket.delete(batch)
                batch.size
            }.getOrElse { error ->
                MmLog.e("Could not remove a batch of attachments", error)
                0
            }
        }
    }

    /** Null when the project is not configured, which is a supported way to run. */
    private fun bucket() = client.get()?.storage?.from(SupabaseClientProvider.Buckets.MEDIA)
}
