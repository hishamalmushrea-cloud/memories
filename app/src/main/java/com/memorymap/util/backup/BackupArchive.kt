package com.memorymap.util.backup

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.documentfile.provider.DocumentFile
import com.memorymap.util.MmLog
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Reads and writes the backup folder through the Storage Access Framework.
 *
 * The user picks the folder, so the app never needs a storage permission and the
 * archive lands wherever the user wants it — a Documents folder, an SD card, a
 * synced directory. That is the point: an archive the app owns is not an archive
 * the user can keep.
 *
 * Every method reports failure instead of throwing. A backup that cannot be
 * written has to be *told* about, and an exception surfacing in a click handler
 * is not a way to tell anyone.
 */
@Singleton
open /**
 * A MIME type that no extension is registered against.
 *
 * `DocumentFile.createFile` appends the extension of whatever MIME type it is
 * given, and it does so unconditionally - asking for `manifest.json` with
 * `application/json` produces `manifest.json.json`. The names of the documents
 * are the format, so anything that renames them makes an archive that is
 * written and then cannot be read back. A MIME type with no extension is the
 * only way to get the name through unchanged. See [createdNamed].
 */
private const val MIME_WITHOUT_EXTENSION = "application/x-memorymap"

class BackupArchive @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    /**
     * The folder the user chose, or null if the permission is gone.
     *
     * Open because it is the one part of this class that cannot be exercised
     * without a real Storage Access Framework grant: everything below it works
     * on any [DocumentFile], so a test can point the archive at an ordinary
     * directory and still cover the writing, the reading and the merging.
     */
    open fun root(treeUri: String): DocumentFile? = runCatching {
        DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
    }.getOrNull()

    fun canWrite(root: DocumentFile): Boolean = root.exists() && root.canWrite()

    fun writeManifest(root: DocumentFile, manifest: BackupManifest): Boolean =
        writeJson(root, BackupLayout.MANIFEST, json.encodeToString(BackupManifest.serializer(), manifest))

    fun writeMemories(root: DocumentFile, rows: List<MemoryBackup>): Boolean =
        writeJson(root, BackupLayout.MEMORIES, json.encodeToString(rows))

    fun writeEntries(root: DocumentFile, rows: List<EntryBackup>): Boolean =
        writeJson(root, BackupLayout.DAILY_ENTRIES, json.encodeToString(rows))

    fun writePeople(root: DocumentFile, rows: List<PersonBackup>): Boolean =
        writeJson(root, BackupLayout.PEOPLE, json.encodeToString(rows))

    fun writePlaces(root: DocumentFile, rows: List<PlaceBackup>): Boolean =
        writeJson(root, BackupLayout.PLACES, json.encodeToString(rows))

    fun writeMedia(root: DocumentFile, rows: List<MediaBackup>): Boolean =
        writeJson(root, BackupLayout.MEDIA, json.encodeToString(rows))

    fun readManifest(root: DocumentFile): BackupManifest? =
        readJson(root, BackupLayout.MANIFEST)
            ?.let { runCatching { json.decodeFromString(BackupManifest.serializer(), it) }.getOrNull() }

    fun readMemories(root: DocumentFile): List<MemoryBackup> =
        readList(root, BackupLayout.MEMORIES, MemoryBackup.serializer())

    fun readEntries(root: DocumentFile): List<EntryBackup> =
        readList(root, BackupLayout.DAILY_ENTRIES, EntryBackup.serializer())

    fun readPeople(root: DocumentFile): List<PersonBackup> =
        readList(root, BackupLayout.PEOPLE, PersonBackup.serializer())

    fun readPlaces(root: DocumentFile): List<PlaceBackup> =
        readList(root, BackupLayout.PLACES, PlaceBackup.serializer())

    fun readMedia(root: DocumentFile): List<MediaBackup> =
        readList(root, BackupLayout.MEDIA, MediaBackup.serializer())

    /** Creates the media folder, or returns the one a previous export left. */
    fun mediaDir(root: DocumentFile): DocumentFile? =
        root.findFile(BackupLayout.MEDIA_DIR)?.takeIf { it.isDirectory }
            ?: root.createDirectory(BackupLayout.MEDIA_DIR)

    fun findMediaFile(root: DocumentFile, archivePath: String): DocumentFile? {
        val name = archivePath.substringAfterLast('/')
        if (name.isBlank()) return null
        return root.findFile(BackupLayout.MEDIA_DIR)?.findFile(name)
    }

    /** Copies a file on this device into the archive. */
    fun copyToArchive(source: File, mediaDir: DocumentFile, name: String): Boolean {
        if (!source.exists()) return false
        // A re-export would otherwise stack `name (1)` documents on top of each
        // other and quietly double the archive.
        mediaDir.findFile(name)?.delete()
        // The attachment's real type is recorded in media.json; the document only
        // has to keep the name the archive refers to it by.
        val target = createdNamed(mediaDir, name) ?: return false
        return copy(Uri.fromFile(source), target.uri)
    }

    /**
     * Creates a document whose name is exactly [name], or null if it could not.
     *
     * The name is checked rather than assumed: a provider is free to adjust what
     * it creates, and an archive whose documents are called something else is an
     * archive that cannot be restored. A rename is reported as a failed write,
     * which the user sees, rather than as a silent one, which they would not.
     */
    private fun createdNamed(root: DocumentFile, name: String): DocumentFile? {
        val file = root.createFile(MIME_WITHOUT_EXTENSION, name)
        if (file == null) {
            MmLog.e("Could not create $name in the archive", null)
            return null
        }
        val actual = file.name
        if (actual != name) {
            file.delete()
            MmLog.e("The backup folder renamed $name to $actual", null)
            return null
        }
        return file
    }

    /** Copies a file out of the archive to a path on this device. */
    fun copyFromArchive(source: DocumentFile, target: File): Boolean {
        target.parentFile?.mkdirs()
        return copy(source.uri, Uri.fromFile(target))
    }

    private fun copy(from: Uri, to: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(from)?.use { input ->
            context.contentResolver.openOutputStream(to)?.use { output ->
                input.copyTo(output)
                true
            }
        } ?: false
    }.getOrElse { error ->
        MmLog.e("Could not copy a backup file", error)
        false
    }

    private fun <T> readList(root: DocumentFile, name: String, serializer: KSerializer<T>): List<T> {
        val text = readJson(root, name) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(serializer), text)
        }.getOrElse { error ->
            // A file that will not parse is reported as empty rather than
            // aborting the whole import: the other documents are still readable.
            MmLog.e("Could not read $name from the archive", error)
            emptyList()
        }
    }

    private fun writeJson(root: DocumentFile, name: String, content: String): Boolean {
        // Replaced rather than merged, so an export never leaves a stale document
        // behind that a later import would read.
        root.findFile(name)?.delete()
        val file = createdNamed(root, name) ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(file.uri)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                true
            } ?: false
        }.getOrElse { error ->
            MmLog.e("Could not write $name to the archive", error)
            false
        }
    }

    private fun readJson(root: DocumentFile, name: String): String? {
        val file = root.findFile(name) ?: return null
        return runCatching {
            context.contentResolver.openInputStream(file.uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse { error ->
            MmLog.e("Could not read $name from the archive", error)
            null
        }
    }
}
