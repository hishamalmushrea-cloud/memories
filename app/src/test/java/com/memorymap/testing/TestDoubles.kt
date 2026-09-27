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
import com.memorymap.data.remote.AccountApi
import com.memorymap.domain.model.CloudRemoval
import com.memorymap.domain.model.SyncState
import com.memorymap.domain.model.WipeSummary
import com.memorymap.domain.repository.SyncRepository
import com.memorymap.domain.repository.UserRepository
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.BackupOutcome
import com.memorymap.domain.repository.BackupRepository
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.domain.repository.MediaRepository
import com.memorymap.domain.repository.MemoryRepository
import com.memorymap.domain.repository.ReferenceRepository
import com.memorymap.domain.repository.SearchRepository
import com.memorymap.domain.model.DayContentCounts
import com.memorymap.domain.model.SearchFilter
import com.memorymap.domain.model.SearchResults
import com.memorymap.domain.usecase.SearchQuery
import com.memorymap.util.backup.BackupCounts
import com.memorymap.util.backup.BackupManifest
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
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

    /**
     * Whether a Supabase project is connected. False by default, which is the
     * install most of these tests describe - an offline account with no server
     * anywhere. A test about the cloud half of a wipe sets it to true.
     */
    var cloudConfigured: Boolean = false

    override val isCloudConfigured: Boolean get() = cloudConfigured

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

    /** What the next [deleteAccount] answers. Change it to drive a failure. */
    var deletionResult: AuthRepository.Deletion = AuthRepository.Deletion.NOT_CONFIGURED

    var deleteAccountCalls: Int = 0
        private set

    /** Set to false to check that a failed deletion does not end the session. */
    var signOutOnDelete: Boolean = true

    override suspend fun deleteAccount(): AuthRepository.Deletion {
        deleteAccountCalls++
        if (deletionResult == AuthRepository.Deletion.DELETED && signOutOnDelete) id.value = null
        return deletionResult
    }
}

/**
 * The two server-side deletions, recorded rather than sent.
 *
 * A wipe has to behave correctly when the server is unreachable, and that is a
 * property of the caller, not of Postgrest: this fails on demand so the caller's
 * answer can be checked without a socket.
 */
/**
 * A user repository that answers with fixed summaries and records the calls.
 *
 * The profile screen's destructive actions are a sequence - uploads first, then
 * the server, then this device - and the sequence is the thing worth testing:
 * this keeps the order and the arguments of every call it received.
 */
class RecordingUserRepository(
    var wipeSummary: WipeSummary = WipeSummary(memories = 3, entries = 2, mediaFiles = 1),
    var cloudRemoval: CloudRemoval = CloudRemoval(removed = 2),
    var serverRecordsRemoved: Boolean = true,
    var uploadedCount: Int = 2,
) : UserRepository {

    /** Every call, in order, as `name(userId)`. */
    val calls: MutableList<String> = mutableListOf()

    override fun watchCurrentUser(): Flow<User?> = flowOf(null)

    override suspend fun getById(id: String): User? = null

    override suspend fun save(user: User) = Unit

    override suspend fun deleteLocalData(userId: String): WipeSummary {
        calls += "deleteLocalData($userId)"
        return wipeSummary
    }

    override suspend fun uploadedAttachmentPaths(userId: String): List<String> {
        calls += "uploadedAttachmentPaths($userId)"
        return List(uploadedCount) { "$userId/attachment-$it.jpg" }
    }

    override suspend fun deleteCloudCopies(userId: String): CloudRemoval {
        calls += "deleteCloudCopies($userId)"
        return cloudRemoval
    }

    override suspend fun deleteServerRecords(): Boolean {
        calls += "deleteServerRecords"
        return serverRecordsRemoved
    }
}

/** A sync double that never runs and reports an idle queue. */
class FakeSyncRepository : SyncRepository {

    override fun watchState(userId: String?): Flow<SyncState> = flowOf(SyncState())

    override suspend fun syncNow(userId: String?): SyncState = SyncState()
}

class RecordingAccountApi(var failing: Boolean = false) : AccountApi {

    var recordsDeletions: Int = 0
        private set

    var accountDeletions: Int = 0
        private set

    override suspend fun deleteRecords() {
        recordsDeletions++
        if (failing) throw IllegalStateException("the server could not be reached")
    }

    override suspend fun deleteAccount() {
        accountDeletions++
        if (failing) throw IllegalStateException("the server could not be reached")
    }
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
 * A memory repository that is real except for its delete, which throws.
 *
 * Written as a decorator over the interface rather than as a hand-written fake, so a
 * method added to `MemoryRepository` arrives here without anyone remembering to add it -
 * and so the shape of "storage refused the delete" is decided in one place and shared by
 * the tests that need it.
 */
class FailingDeleteMemoryRepository(
    private val delegate: MemoryRepository,
) : MemoryRepository by delegate {
    override suspend fun delete(id: String): Unit =
        throw IllegalStateException("the database refused the delete")
}

/** The same idea for removing one attachment. */
class FailingRemoveMediaRepository(
    private val delegate: MediaRepository,
) : MediaRepository by delegate {
    override suspend fun remove(id: String): Unit =
        throw IllegalStateException("the file is in use")
}

/**
 * A reference repository that is real except for the calls a test asks to fail.
 *
 * It is written as a delegation over the interface rather than as its own
 * implementation, so a method added to the interface cannot quietly go missing
 * here, and what a failing storage call looks like from a view model's side is
 * decided in one place.
 */
class FailableReferenceRepository(
    private val delegate: ReferenceRepository = FakeReferenceRepository(),
    private val failAdds: Boolean = true,
    private val failDeletes: Boolean = false,
) : ReferenceRepository {

    /** How many times an add was attempted, however it ended. */
    var addAttempts: Int = 0
        private set

    override fun watchPeople(userId: String): Flow<List<Person>> = delegate.watchPeople(userId)

    override fun watchPlaces(userId: String): Flow<List<Place>> = delegate.watchPlaces(userId)

    override suspend fun findOrCreatePerson(userId: String, name: String): Person {
        addAttempts++
        if (failAdds) throw IllegalStateException("the disk is full")
        return delegate.findOrCreatePerson(userId, name)
    }

    override suspend fun savePlace(place: Place) {
        addAttempts++
        if (failAdds) throw IllegalStateException("the disk is full")
        delegate.savePlace(place)
    }

    override suspend fun deletePerson(id: String) {
        if (failDeletes) throw IllegalStateException("the row is referenced")
        delegate.deletePerson(id)
    }

    override suspend fun deletePlace(id: String) {
        if (failDeletes) throw IllegalStateException("the row is referenced")
        delegate.deletePlace(id)
    }

    override suspend fun entriesWithPerson(personId: String): List<DailyEntry> =
        delegate.entriesWithPerson(personId)

    override suspend fun memoriesWithPerson(personId: String): List<Memory> =
        delegate.memoriesWithPerson(personId)

    override suspend fun entriesAtPlace(placeId: String): List<DailyEntry> =
        delegate.entriesAtPlace(placeId)

    override suspend fun memoriesAtPlace(placeId: String): List<Memory> =
        delegate.memoriesAtPlace(placeId)
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
        if (mediaUpsertsFail) throw IllegalStateException("the media row was refused")
        mediaSent += rows
    }

    override suspend fun upsertDiaryNotes(rows: List<DiaryNoteRecord>) {
        diaryNotesSent += rows
    }

    override suspend fun fetchDiaryNotes(userId: String, since: String?) = diaryNotesToReturn

    override suspend fun fetchMedia(userId: String, since: String?) = mediaToReturn

    /**
     * When set, writing a media row fails - the bytes are in the bucket and the
     * row that describes them never reaches the server.
     */
    var mediaUpsertsFail = false
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

    /** Every key an upload was asked for, repeats included. */
    val uploadCalls = mutableListOf<String>()

    /** When set, every call fails, as an unreachable bucket would. */
    var failing = false

    override suspend fun upload(objectKey: String, bytes: ByteArray): Boolean {
        uploadCalls += objectKey
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


/**
 * An auth repository that remembers what it was asked to do.
 *
 * `FakeAuthRepository` answers "who is signed in" and nothing else, which is all most
 * tests need. The sign-in screen needs more than that: whether a second tap while a
 * request is in flight sends a second request, whether the email was trimmed before it
 * was sent, and which call each mode made. Those are questions about the calls, so this
 * double keeps them.
 *
 * The results are settable rather than fixed, because a failure is a case the screen has
 * to render and not an exception to avoid.
 */
class RecordingAuthRepository(
    userId: String? = "user-1",
    var signInResult: AuthRepository.Result = AuthRepository.Result.Success,
    var signUpResult: AuthRepository.Result = AuthRepository.Result.Success,
    var resetResult: AuthRepository.Result = AuthRepository.Result.Success,
) : AuthRepository {

    private val id = MutableStateFlow(userId)

    /** Every call this double received, in order, as `name(argument, ...)`. */
    val calls = mutableListOf<String>()

    override val currentUserId: StateFlow<String?> = id.asStateFlow()

    override val authState: StateFlow<AuthState> = MutableStateFlow(
        if (userId == null) AuthState.SignedOut
        else AuthState.SignedIn(User(id = userId, email = "", displayName = ""), false),
    )

    override val isCloudConfigured: Boolean = true

    override suspend fun restoreSession() {
        calls += "restoreSession"
    }

    override suspend fun signUp(email: String, password: String, displayName: String): AuthRepository.Result {
        calls += "signUp($email,$password,$displayName)"
        return signUpResult
    }

    override suspend fun signIn(email: String, password: String): AuthRepository.Result {
        calls += "signIn($email,$password)"
        return signInResult
    }

    override suspend fun resetPassword(email: String): AuthRepository.Result {
        calls += "resetPassword($email)"
        return resetResult
    }

    override suspend fun signOut() {
        calls += "signOut"
        id.value = null
    }

    override suspend fun continueOffline(displayName: String?): User {
        calls += "continueOffline(${displayName.orEmpty()})"
        val user = User(id = id.value ?: "local-user", email = "", displayName = displayName.orEmpty())
        id.value = user.id
        return user
    }

    override suspend fun deleteAccount(): AuthRepository.Deletion {
        calls += "deleteAccount"
        return AuthRepository.Deletion.NOT_CONFIGURED
    }
}

/**
 * A backup repository with settable answers.
 *
 * The export/import screen is a sequence of decisions - what the archive says, whether it
 * is a format this build can read, and what happens after the person confirms - and every
 * one of them has a branch that only runs when the answer is not the happy one. This
 * double lets a test choose the answer per call and check what the screen did with it.
 */
class RecordingBackupRepository(
    var exportOutcome: BackupOutcome = BackupOutcome.Exported(backupManifest(), 0, 0),
    var importOutcome: BackupOutcome = BackupOutcome.Imported(BackupCounts(), 0, 0),
    var manifestToInspect: BackupManifest? = backupManifest(),
) : BackupRepository {

    val calls = mutableListOf<String>()

    /** Everything [inspect] was asked about, so a test can see the folder that was picked. */
    val inspected = mutableListOf<String>()

    override suspend fun export(userId: String, treeUri: String): BackupOutcome {
        calls += "export($userId,$treeUri)"
        return exportOutcome
    }

    override suspend fun inspect(treeUri: String): BackupManifest? {
        calls += "inspect($treeUri)"
        inspected += treeUri
        return manifestToInspect
    }

    override suspend fun import(userId: String, treeUri: String): BackupOutcome {
        calls += "import($userId,$treeUri)"
        return importOutcome
    }
}

/**
 * A manifest that `BackupPlanner.validate` accepts.
 *
 * Top-level rather than a companion member, because a companion member cannot be used in
 * the default value of the constructor it belongs to without adding a receiver the value
 * does not need.
 */
fun backupManifest(
    formatVersion: Int = 1,
    createdAtEpochMs: Long = 1_700_000_000_000L,
): BackupManifest = BackupManifest(
    formatVersion = formatVersion,
    appVersion = "0.1.0",
    createdAtEpochMs = createdAtEpochMs,
    counts = BackupCounts(memories = 3, dailyEntries = 2, people = 1, places = 1),
)

/**
 * A diary repository whose period flow the test drives.
 *
 * The week/month/year pages are one aggregation with three windows, so what is worth
 * checking is the window: that a month asks for its first and last day, that the same
 * window twice does not restart the collection, and that a new window replaces the old
 * one. This records the windows and emits whatever the test pushes.
 *
 * The methods the period screens never call are implemented to fail loudly rather than to
 * return something plausible, because a double that answers a question nobody asked can
 * hide the day the question is asked for real.
 */
class RecordingDiaryRepository : DiaryRepository {

    /** Every `watchDayCounts` window, in order. */
    val windows = mutableListOf<Pair<LocalDate, LocalDate>>()

    /** The counts the next collection emits. */
    var counts: List<DayContentCounts> = emptyList()

    /** When true, the flow fails instead of emitting: the screen must survive that. */
    var fails: Boolean = false

    private val emissions = MutableSharedFlow<List<DayContentCounts>>(replay = 1)

    override fun watchDayCounts(
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<DayContentCounts>> {
        windows += from to to
        if (fails) return kotlinx.coroutines.flow.flow { throw IllegalStateException("the database is closed") }
        return emissions
    }

    /** Pushes one emission to whoever is collecting. */
    suspend fun emit(values: List<DayContentCounts>) {
        counts = values
        emissions.emit(values)
    }

    override fun watchDay(userId: String, date: LocalDate) = unused("watchDay")
    override fun watchRange(userId: String, from: LocalDate, to: LocalDate) = unused("watchRange")
    override fun watchLocated(userId: String) = unused("watchLocated")
    override suspend fun getEntry(id: String) = unused("getEntry")
    override suspend fun saveEntry(entry: DailyEntry, personIds: List<String>, placeIds: List<String>) =
        unused("saveEntry")
    override suspend fun peopleOf(entryId: String) = unused("peopleOf")
    override suspend fun placesOf(entryId: String) = unused("placesOf")
    override suspend fun deleteEntry(id: String) = unused("deleteEntry")
    override suspend fun getDiaryNote(userId: String, date: LocalDate) = unused("getDiaryNote")
    override suspend fun saveDiaryNote(userId: String, date: LocalDate, text: String) = unused("saveDiaryNote")
    override suspend fun search(userId: String, query: String) = unused("search")

    private fun unused(name: String): Nothing =
        throw AssertionError("RecordingDiaryRepository.$name was called by a period screen, which never should")
}

/**
 * A search repository that records the queries it was given.
 *
 * The search box has two behaviours worth proving: the query that reaches the repository is
 * the *parsed* one (so `مع أحمد` becomes a person filter rather than a text scan), and a
 * burst of typing produces one call rather than one per keystroke.
 */
class RecordingSearchRepository(
    var results: (SearchQuery) -> SearchResults = { SearchResults(query = it) },
) : SearchRepository {

    val queries = mutableListOf<SearchQuery>()
    val filters = mutableListOf<SearchFilter>()

    /** When true, the next search throws - the screen must survive a failing database. */
    var fails: Boolean = false

    override suspend fun search(
        userId: String,
        query: SearchQuery,
        filter: SearchFilter,
    ): SearchResults {
        queries += query
        filters += filter
        if (fails) throw IllegalStateException("the query plan failed")
        return results(query)
    }
}
