package com.memorymap.testing

import com.memorymap.domain.model.AuthState
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.Person
import com.memorymap.domain.model.Place
import com.memorymap.domain.model.User
import com.memorymap.data.remote.EntryPersonLink
import com.memorymap.data.remote.EntryPlaceLink
import com.memorymap.data.remote.EntryRecord
import com.memorymap.data.remote.DiaryNoteRecord
import com.memorymap.data.local.MediaFileStore
import com.memorymap.data.remote.MediaRecord
import com.memorymap.data.remote.MediaStorage
import com.memorymap.data.remote.MemoryPersonLink
import com.memorymap.data.remote.MemoryPlaceLink
import com.memorymap.data.remote.MemoryRecord
import com.memorymap.data.remote.PersonRecord
import com.memorymap.data.remote.PlaceRecord
import com.memorymap.data.remote.SyncApi
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.ReferenceRepository
import com.memorymap.util.ImageOptimizer
import com.memorymap.util.UploadBytes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal auth double: a fixed account id and no network.
 *
 * ViewModel tests need "who is signed in" and nothing else, so this replaces the
 * Supabase-backed repository without touching a socket.
 */
class FakeAuthRepository(userId: String?) : AuthRepository {

    private val id = MutableStateFlow(userId)

    override val currentUserId: StateFlow<String?> = id.asStateFlow()

    override val authState: StateFlow<AuthState> = MutableStateFlow(
        if (userId == null) {
            AuthState.SignedOut
        } else {
            AuthState.SignedIn(User(id = userId, email = "", displayName = ""), false)
        },
    )

    override val isCloudConfigured: Boolean = false

    override suspend fun restoreSession() = Unit

    override suspend fun signUp(email: String, password: String, displayName: String) =
        AuthRepository.Result.Success

    override suspend fun signIn(email: String, password: String) = AuthRepository.Result.Success

    override suspend fun resetPassword(email: String) = AuthRepository.Result.Success

    override suspend fun signOut() {
        id.value = null
    }

    override suspend fun continueOffline(displayName: String?): User =
        User(id = id.value ?: "local-user", email = "", displayName = displayName.orEmpty())
}

/**
 * Minimal people-and-places double.
 *
 * The editors only need a list to offer and somewhere for a created name to
 * land, so this keeps both in memory and never touches a database.
 */
class FakeReferenceRepository(
    people: List<Person> = emptyList(),
    places: List<Place> = emptyList(),
) : ReferenceRepository {

    private val people = people.toMutableList()
    private val places = places.toMutableList()

    /** Every name handed back by [findOrCreatePerson], in the order they were made. */
    val createdPeople: List<Person> get() = people

    override fun watchPeople(userId: String): Flow<List<Person>> = flowOf(people.toList())

    override fun watchPlaces(userId: String): Flow<List<Place>> = flowOf(places.toList())

    override suspend fun findOrCreatePerson(userId: String, name: String): Person =
        people.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: Person(userId = userId, name = name).also { people.add(it) }

    override suspend fun savePlace(place: Place) {
        places.add(place)
    }

    override suspend fun deletePerson(id: String) {
        people.removeAll { it.id == id }
    }

    override suspend fun deletePlace(id: String) {
        places.removeAll { it.id == id }
    }

    override suspend fun entriesWithPerson(personId: String): List<DailyEntry> = emptyList()

    override suspend fun memoriesWithPerson(personId: String): List<Memory> = emptyList()

    override suspend fun entriesAtPlace(placeId: String): List<DailyEntry> = emptyList()

    override suspend fun memoriesAtPlace(placeId: String): List<Memory> = emptyList()
}

/**
 * A sync double that records what it was asked to send and serves back whatever
 * a test queued for it.
 *
 * Keeping the two halves separate is what makes a test able to say "the phone
 * had this, the server had that" without a network anywhere near it.
 */
class RecordingSyncApi : SyncApi {

    val memoriesSent = mutableListOf<MemoryRecord>()
    val entriesSent = mutableListOf<EntryRecord>()
    val peopleSent = mutableListOf<PersonRecord>()
    val placesSent = mutableListOf<PlaceRecord>()
    val mediaSent = mutableListOf<MediaRecord>()
    val diaryNotesSent = mutableListOf<DiaryNoteRecord>()

    /** Each replace call, so a test can tell an unlink from a no-op. */
    val memoryPeopleReplacements = mutableListOf<Pair<List<String>, List<MemoryPersonLink>>>()
    val memoryPlaceReplacements = mutableListOf<Pair<List<String>, List<MemoryPlaceLink>>>()
    val entryPeopleReplacements = mutableListOf<Pair<List<String>, List<EntryPersonLink>>>()
    val entryPlaceReplacements = mutableListOf<Pair<List<String>, List<EntryPlaceLink>>>()

    /** What the server is meant to hand back on the next fetch. */
    var memoriesToReturn: List<MemoryRecord> = emptyList()
    var entriesToReturn: List<EntryRecord> = emptyList()
    var memoryPeopleToReturn: List<MemoryPersonLink> = emptyList()
    var memoryPlacesToReturn: List<MemoryPlaceLink> = emptyList()
    var entryPeopleToReturn: List<EntryPersonLink> = emptyList()
    var entryPlacesToReturn: List<EntryPlaceLink> = emptyList()
    var mediaToReturn: List<MediaRecord> = emptyList()
    var diaryNotesToReturn: List<DiaryNoteRecord> = emptyList()

    override suspend fun upsertMemories(rows: List<MemoryRecord>) {
        memoriesSent += rows
    }

    override suspend fun upsertEntries(rows: List<EntryRecord>) {
        entriesSent += rows
    }

    override suspend fun fetchMemories(userId: String, since: String?) = memoriesToReturn

    override suspend fun fetchEntries(userId: String, since: String?) = entriesToReturn

    override suspend fun upsertPeople(rows: List<PersonRecord>) {
        peopleSent += rows
    }

    override suspend fun upsertPlaces(rows: List<PlaceRecord>) {
        placesSent += rows
    }

    override suspend fun fetchPeople(userId: String, since: String?) = emptyList<PersonRecord>()

    override suspend fun fetchPlaces(userId: String, since: String?) = emptyList<PlaceRecord>()

    override suspend fun replaceMemoryPeople(memoryIds: List<String>, links: List<MemoryPersonLink>) {
        memoryPeopleReplacements += memoryIds to links
    }

    override suspend fun replaceMemoryPlaces(memoryIds: List<String>, links: List<MemoryPlaceLink>) {
        memoryPlaceReplacements += memoryIds to links
    }

    override suspend fun replaceEntryPeople(entryIds: List<String>, links: List<EntryPersonLink>) {
        entryPeopleReplacements += entryIds to links
    }

    override suspend fun replaceEntryPlaces(entryIds: List<String>, links: List<EntryPlaceLink>) {
        entryPlaceReplacements += entryIds to links
    }

    override suspend fun fetchMemoryPeople(memoryIds: List<String>) = memoryPeopleToReturn

    override suspend fun fetchMemoryPlaces(memoryIds: List<String>) = memoryPlacesToReturn

    override suspend fun fetchEntryPeople(entryIds: List<String>) = entryPeopleToReturn

    override suspend fun fetchEntryPlaces(entryIds: List<String>) = entryPlacesToReturn

    override suspend fun upsertMedia(rows: List<MediaRecord>) {
        mediaSent += rows
    }

    override suspend fun upsertDiaryNotes(rows: List<DiaryNoteRecord>) {
        diaryNotesSent += rows
    }

    override suspend fun fetchDiaryNotes(userId: String, since: String?) = diaryNotesToReturn

    override suspend fun fetchMedia(userId: String, since: String?) = mediaToReturn
}

/**
 * A bucket in memory.
 *
 * Enough to tell an attachment that was uploaded from one that was not, and to
 * make an upload fail on demand, which is the only way a test can reach the
 * retry path without a project.
 */
class RecordingMediaStorage : MediaStorage {

    val objects = mutableMapOf<String, ByteArray>()
    val removed = mutableListOf<String>()

    /** When set, every call fails, as an unreachable bucket would. */
    var failing = false

    override suspend fun upload(objectKey: String, bytes: ByteArray): Boolean {
        if (failing) return false
        objects[objectKey] = bytes
        return true
    }

    override suspend fun download(objectKey: String): ByteArray? {
        if (failing) return null
        return objects[objectKey]
    }

    override suspend fun remove(objectKey: String): Boolean {
        if (failing) return false
        removed += objectKey
        objects.remove(objectKey)
        return true
    }

    override suspend fun removeAll(objectKeys: List<String>): Int {
        if (failing) return 0
        // Counts what it was asked to remove, as the real bucket does: the
        // request either succeeds for the batch or throws for it.
        removed += objectKeys
        objectKeys.forEach { objects.remove(it) }
        return objectKeys.size
    }
}

/**
 * Attachment bytes on a real directory, with no Android context.
 *
 * The production store adds the app's private folder and a naming rule; what a
 * sync test needs is somewhere for the bytes to actually land.
 */
class TemporaryMediaFileStore(private val root: java.io.File) : MediaFileStore {

    override fun exists(localPath: String) = java.io.File(localPath).exists()

    override fun read(localPath: String) = runCatching {
        java.io.File(localPath).takeIf { it.exists() }?.readBytes()
    }.getOrNull()

    override fun pathFor(
        type: MediaType,
        ownerId: String,
        mediaId: String,
        extension: String,
    ): String = java.io.File(root, "${ownerId}_$mediaId.$extension").absolutePath

    override fun write(localPath: String, bytes: ByteArray): Boolean = runCatching {
        val file = java.io.File(localPath)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        true
    }.getOrDefault(false)
}

/**
 * An optimizer that hands the uploader whatever the test tells it to.
 *
 * It answers a question no decoder is needed to settle: does an upload put the
 * prepared bytes in the bucket, under the extension that came with them, and
 * leave the file on the device untouched? Left unset it passes the file through
 * unchanged, which is what keeps every other upload test about the bucket rather
 * than about this one.
 */
class RecordingImageOptimizer : ImageOptimizer {

    /** The bytes to hand over instead of the file's, or null to pass them through. */
    var replacement: ByteArray? = null

    /** The extension to hand over with [replacement]. */
    var extension: String = "jpg"

    /** Every path the uploader asked about. */
    val asked = mutableListOf<String>()

    override fun prepare(
        path: String,
        type: MediaType,
        bytes: ByteArray,
        fallbackExtension: String,
    ): UploadBytes {
        asked += path
        val prepared = replacement ?: return UploadBytes(bytes, fallbackExtension)
        return UploadBytes(prepared, extension)
    }
}
