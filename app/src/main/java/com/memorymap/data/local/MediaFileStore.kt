package com.memorymap.data.local

import android.content.Context
import com.memorymap.domain.model.MediaType
import com.memorymap.util.MediaStore
import com.memorymap.util.MmLog
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The bytes of an attachment, on this device.
 *
 * A port so that the sync layer can move attachments to and from the cloud
 * without holding an Android context: what it needs to know is whether a file
 * is here, where it would go, and how to read or write it.
 */
interface MediaFileStore {

    fun exists(localPath: String): Boolean

    /** The bytes at [localPath], or null when the file is not there. */
    fun read(localPath: String): ByteArray?

    /**
     * Where an attachment of this identity belongs on this device.
     *
     * Derived from the ids rather than from the clock, so the same attachment
     * has the same path on every device. That is what lets a row arriving from
     * the cloud name its file before the bytes have been fetched.
     */
    fun pathFor(type: MediaType, ownerId: String, mediaId: String, extension: String): String

    /** Writes [bytes] to [localPath], creating the folder. Reports success. */
    fun write(localPath: String, bytes: ByteArray): Boolean
}

@Singleton
class LocalMediaFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : MediaFileStore {

    override fun exists(localPath: String): Boolean =
        localPath.isNotBlank() && File(localPath).exists()

    override fun read(localPath: String): ByteArray? = runCatching {
        File(localPath).takeIf { it.exists() }?.readBytes()
    }.getOrElse { error ->
        MmLog.e("Could not read an attachment file", error)
        null
    }

    override fun pathFor(
        type: MediaType,
        ownerId: String,
        mediaId: String,
        extension: String,
    ): String = MediaStore.derivedFile(context, type, ownerId, mediaId, extension).absolutePath

    override fun write(localPath: String, bytes: ByteArray): Boolean = runCatching {
        val file = File(localPath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    }.getOrElse { error ->
        MmLog.e("Could not write an attachment file", error)
        false
    }
}
