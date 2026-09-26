package com.memorymap.domain.repository

import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaSummary
import com.memorymap.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

/**
 * Attachments of a memory or a diary entry.
 *
 * Photos, audio and video are plain files: they are stored, listed, played back
 * and deleted. Nothing here transcribes audio or analyses video.
 *
 * Files live in app-private storage, so the archive survives a cache clear and
 * needs no storage permission. Cloud upload stays optional and explicit.
 */
interface MediaRepository {

    /** Live attachments of one owner, oldest first. */
    fun watchFor(owner: MediaOwner, ownerId: String): Flow<List<MediaItem>>

    /** One-shot read of the live attachments of one owner. */
    suspend fun getFor(owner: MediaOwner, ownerId: String): List<MediaItem>

    /** Registers an attachment whose file is already in app-private storage. */
    suspend fun attach(item: MediaItem)

    /**
     * Removes one attachment: the row becomes a tombstone and the file is
     * deleted. The tombstone is what keeps the delete from being undone by the
     * next sync.
     */
    suspend fun remove(id: String)

    /** Removes every attachment of one owner, used when the owner is deleted. */
    suspend fun removeAllFor(owner: MediaOwner, ownerId: String)

    /** Live attachment counts per type, for the profile statistics. */
    suspend fun countByType(): Map<MediaType, Int>

    /** Attachment counters keyed by owner id, for lists that show thumbnails. */
    fun watchSummary(owner: MediaOwner): Flow<Map<String, MediaSummary>>

    /**
     * Deletes the file of an attachment that was never saved, used when the user
     * leaves an editor without saving. No row exists, so none is written.
     */
    suspend fun discard(item: MediaItem)

    /**
     * Asks for one attachment to be sent to the cloud.
     *
     * This is the only way an attachment ever leaves this device. Nothing here
     * uploads anything by itself: the request is recorded and the sync worker
     * carries it out, so the answer survives a restart and the attachment can
     * show that it is waiting.
     */
    suspend fun requestUpload(id: String)

    /**
     * Withdraws that request.
     *
     * Refused once the bytes are in the bucket, because clearing the request
     * then would leave an object nothing on the server points at. Removing an
     * uploaded attachment deletes the object through the ordinary tombstone
     * path, which is what is left.
     */
    suspend fun cancelUpload(id: String)
}
