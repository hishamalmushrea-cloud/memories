package com.memorymap.ui.memories

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.R
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.model.MediaItem
import com.memorymap.domain.model.MediaOwner
import com.memorymap.domain.model.MediaType
import com.memorymap.domain.model.Person
import com.memorymap.domain.model.Place
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.Visibility
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.navigation.Routes
import com.memorymap.domain.repository.MediaRepository
import com.memorymap.domain.repository.ReferenceRepository
import com.memorymap.domain.repository.MemoryRepository
import com.memorymap.util.MediaImporter
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** UI state of the memory editor, used for both create and edit. */
data class MemoryEditorUiState(
    val memoryId: String,
    val isNew: Boolean = true,
    val title: String = "",
    val text: String = "",
    val date: LocalDate = LocalDate.now(),
    val emotion: Emotion = Emotion.NOSTALGIA,
    val visibility: Visibility = Visibility.PRIVATE,
    val placeName: String = "",
    /** Every name this user has, so the picker can offer them. */
    val people: List<Person> = emptyList(),
    val places: List<Place> = emptyList(),
    /** The subset actually linked to the record being edited. */
    val personIds: Set<String> = emptySet(),
    val placeIds: Set<String> = emptySet(),
    /** Chosen on the map, or carried over from the memory being edited. */
    val location: GeoPoint? = null,
    /** Attachments already stored for this memory. */
    val savedAttachments: List<MediaItem> = emptyList(),
    /** Files copied into the archive but not written to the database yet. */
    val pendingAttachments: List<MediaItem> = emptyList(),
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorRes: Int? = null,
) {
    val attachments: List<MediaItem> get() = savedAttachments + pendingAttachments
}

/**
 * Creates and edits one memory.
 *
 * The id exists before the first save, so attachments can be copied into the
 * archive straight away; they are only written to the database when the user
 * saves. Leaving without saving deletes those files, which is why nothing is
 * ever left behind in app-private storage.
 */
@HiltViewModel
class MemoryEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val memoryRepository: MemoryRepository,
    private val mediaRepository: MediaRepository,
    private val authRepository: AuthRepository,
    private val referenceRepository: ReferenceRepository,
) : ViewModel() {

    private val memoryId: String = savedStateHandle.get<String>("memoryId")
        ?.takeIf { it.isNotBlank() }
        ?: UUID.randomUUID().toString()

    private val _state = MutableStateFlow(MemoryEditorUiState(memoryId = memoryId))
    val state: StateFlow<MemoryEditorUiState> = _state.asStateFlow()

    init {
        // The picker hands its result back through the saved state of the entry
        // that opened it, so it is read here and cleared to avoid re-applying it.
        savedStateHandle.getStateFlow<String?>(Routes.RESULT_LOCATION, null)
            .onEach { raw ->
                Routes.parseLocation(raw)?.let { (latitude, longitude) ->
                    _state.update { it.copy(location = GeoPoint(latitude, longitude)) }
                }
                savedStateHandle[Routes.RESULT_LOCATION] = null
            }
            .launchIn(viewModelScope)

        authRepository.currentUserId
            .flatMapLatest { userId ->
                if (userId == null) {
                    flowOf(emptyList<Person>() to emptyList<Place>())
                } else {
                    combine(
                        referenceRepository.watchPeople(userId),
                        referenceRepository.watchPlaces(userId),
                    ) { people, places -> people to places }
                }
            }
            .onEach { (people, places) ->
                _state.update { it.copy(people = people, places = places) }
            }
            .launchIn(viewModelScope)

        viewModelScope.launch {
            // Links are loaded before the body so an edit shows what is already there.
            runCatching {
                memoryRepository.peopleOf(memoryId) to memoryRepository.placesOf(memoryId)
            }.getOrNull()?.let { (people, places) ->
                _state.update { it.copy(personIds = people.toSet(), placeIds = places.toSet()) }
            }

            val existing = runCatching { memoryRepository.getById(memoryId) }.getOrNull()
            if (existing == null) return@launch
            val attachments = runCatching {
                mediaRepository.getFor(MediaOwner.MEMORY, memoryId)
            }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    isNew = false,
                    title = existing.title,
                    text = existing.text,
                    date = existing.memoryDate,
                    emotion = existing.emotion,
                    visibility = existing.visibility,
                    placeName = existing.placeName.orEmpty(),
                    // A pinned location survives an edit of anything else.
                    location = it.location ?: existing.location,
                    savedAttachments = attachments,
                )
            }
        }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value, errorRes = null) }

    fun onTextChange(value: String) = _state.update { it.copy(text = value) }

    fun onDateChange(value: LocalDate) = _state.update { it.copy(date = value) }

    fun onEmotionChange(value: Emotion) = _state.update { it.copy(emotion = value) }

    fun onVisibilityChange(value: Visibility) = _state.update { it.copy(visibility = value) }

    fun onPlaceNameChange(value: String) = _state.update { it.copy(placeName = value) }

    fun togglePerson(id: String) = _state.update {
        it.copy(personIds = if (id in it.personIds) it.personIds - id else it.personIds + id)
    }

    fun togglePlace(id: String) = _state.update {
        it.copy(placeIds = if (id in it.placeIds) it.placeIds - id else it.placeIds + id)
    }

    /**
     * Creates a person from the name typed in the editor and links it at once.
     *
     * Finding rather than creating keeps one person per name, so `كل الأحداث مع
     * أحمد` keeps returning one person rather than several spellings of the same one.
     */
    fun createPerson(name: String) {
        val userId = authRepository.currentUserId.value ?: return
        viewModelScope.launch {
            runCatching { referenceRepository.findOrCreatePerson(userId, name) }
                .onSuccess { person -> _state.update { it.copy(personIds = it.personIds + person.id) } }
                .onFailure { MmLog.e("Could not add the person", it) }
        }
    }

    /**
     * Creates a place from the name and the memory's own pin.
     *
     * A place without coordinates cannot be shown on the map, which is why this
     * is only offered once the memory has a location.
     */
    fun createPlace(name: String) {
        val userId = authRepository.currentUserId.value ?: return
        val location = _state.value.location
        if (location == null) {
            _state.update { it.copy(errorRes = R.string.memory_error_place_needs_location) }
            return
        }
        viewModelScope.launch {
            runCatching {
                referenceRepository.savePlace(
                    Place(userId = userId, name = name, location = location),
                )
            }
                .onSuccess { place -> _state.update { it.copy(placeIds = it.placeIds + place.id) } }
                .onFailure { MmLog.e("Could not add the place", it) }
        }
    }

    /** Drops the pin; the memory then simply has no location. */
    fun clearLocation() = _state.update { it.copy(location = null) }

    fun clearError() = _state.update { it.copy(errorRes = null) }

    /** Copies a picked photo, audio or video into the archive. */
    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching { MediaImporter.import(context, uri, MediaOwner.MEMORY, memoryId) }
                    .onFailure { MmLog.e("Unable to import the attachment", it) }
                    .getOrNull()
            }
            if (imported == null) {
                _state.update { it.copy(errorRes = R.string.memory_error_media) }
            } else {
                _state.update { it.copy(pendingAttachments = it.pendingAttachments + imported) }
            }
        }
    }

    /** Registers a file the app captured itself: a camera photo or a voice note. */
    fun adoptCapture(file: File, type: MediaType, mimeType: String? = null) {
        viewModelScope.launch {
            val adopted = withContext(Dispatchers.IO) {
                MediaImporter.adopt(file, type, MediaOwner.MEMORY, memoryId, mimeType)
            }
            if (adopted == null) {
                _state.update { it.copy(errorRes = R.string.memory_error_media) }
            } else {
                _state.update { it.copy(pendingAttachments = it.pendingAttachments + adopted) }
            }
        }
    }

    /** Removes an attachment: a stored one becomes a tombstone, a pending one is deleted. */
    fun removeAttachment(item: MediaItem) {
        viewModelScope.launch {
            runCatching {
                if (_state.value.savedAttachments.any { it.id == item.id }) {
                    mediaRepository.remove(item.id)
                    _state.update { it.copy(savedAttachments = it.savedAttachments - item) }
                } else {
                    mediaRepository.discard(item)
                    _state.update { it.copy(pendingAttachments = it.pendingAttachments - item) }
                }
            }.onFailure { MmLog.e("Unable to remove the attachment", it) }
        }
    }

    /** Writes the memory and then its pending attachments. */
    fun save() {
        val current = _state.value
        if (current.isSaving || current.isSaved) return
        if (current.title.isBlank()) {
            _state.update { it.copy(errorRes = R.string.memory_error_title) }
            return
        }
        viewModelScope.launch {
            val userId = authRepository.currentUserId.value
            if (userId == null) {
                _state.update { it.copy(errorRes = R.string.memory_error_no_account) }
                return@launch
            }
            _state.update { it.copy(isSaving = true) }
            val result = runCatching {
                memoryRepository.save(
                    Memory(
                        id = memoryId,
                        userId = userId,
                        title = current.title.trim(),
                        text = current.text.trim(),
                        memoryDate = current.date,
                        emotion = current.emotion,
                        visibility = current.visibility,
                        placeName = current.placeName.trim().ifBlank { null },
                        location = current.location,
                    ),
                    personIds = current.personIds.toList(),
                    placeIds = current.placeIds.toList(),
                )
                current.pendingAttachments.forEach { mediaRepository.attach(it) }
            }
            result
                .onSuccess { _state.update { it.copy(isSaving = false, isSaved = true) } }
                .onFailure { error ->
                    MmLog.e("Unable to save the memory", error)
                    _state.update { it.copy(isSaving = false, errorRes = R.string.memory_error_save) }
                }
        }
    }

    /** Soft-deletes the memory and its attachments, then closes the editor. */
    fun delete() {
        viewModelScope.launch {
            runCatching {
                memoryRepository.delete(memoryId)
                mediaRepository.removeAllFor(MediaOwner.MEMORY, memoryId)
            }
                .onFailure { MmLog.e("Unable to delete the memory", it) }
                .onSuccess { _state.update { it.copy(isSaved = true) } }
        }
    }

    /**
     * Files imported during an abandoned edit never reached the database, so
     * they are removed here. The view model scope is already cancelled at this
     * point, which is why the cleanup runs on a detached scope.
     */
    override fun onCleared() {
        super.onCleared()
        if (_state.value.isSaved) return
        val orphans = _state.value.pendingAttachments
        if (orphans.isEmpty()) return
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            orphans.forEach { runCatching { mediaRepository.discard(it) } }
        }
    }
}
