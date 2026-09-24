package com.memorymap.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.model.Person
import com.memorymap.domain.model.Place
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.ReferenceRepository
import com.memorymap.navigation.Routes
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class OrganizationUiState(
    val people: List<Person> = emptyList(),
    val places: List<Place> = emptyList(),
    /** How many records each name is linked to, so a row can be judged at a glance. */
    val personCounts: Map<String, Int> = emptyMap(),
    val placeCounts: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = true,
    /** The name typed into the "add" field, kept here so it survives a rotation. */
    val draftName: String = "",
    /** Coordinates chosen for the place being added, before it is saved. */
    val draftLocation: GeoPoint? = null,
)

/**
 * The people and places an archive is organised around.
 *
 * A name is created once here and then linked to as many records as mention it,
 * which is what makes `كل الأحداث مع أحمد` a question the database can actually
 * answer rather than a guess at a text match.
 */
@HiltViewModel
class OrganizationViewModel @Inject constructor(
    private val referenceRepository: ReferenceRepository,
    private val authRepository: AuthRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val drafts = MutableStateFlow(OrganizationUiState())

    val state: StateFlow<OrganizationUiState> = authRepository.currentUserId
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(OrganizationUiState(isLoading = false))
            } else {
                combine(listsFor(userId), drafts) { lists, draft ->
                    draft.copy(
                        people = lists.people,
                        places = lists.places,
                        personCounts = lists.personCounts,
                        placeCounts = lists.placeCounts,
                        isLoading = false,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OrganizationUiState())

    init {
        // A location chosen on the picker comes back through the navigation
        // stack. The key is cleared once read, so a stale pick is never applied
        // to the next place.
        savedStateHandle.getStateFlow<String?>(Routes.RESULT_LOCATION, null)
            .onEach { raw ->
                Routes.parseLocation(raw)?.let { (lat, lon) ->
                    drafts.value = drafts.value.copy(draftLocation = GeoPoint(lat, lon))
                }
                savedStateHandle[Routes.RESULT_LOCATION] = null
            }
            .launchIn(viewModelScope)
    }

    fun onDraftNameChanged(name: String) {
        drafts.value = drafts.value.copy(draftName = name)
    }

    fun addPerson() {
        val name = drafts.value.draftName.trim()
        val userId = authRepository.currentUserId.value
        if (name.isEmpty() || userId == null) return
        viewModelScope.launch {
            // Finding rather than creating keeps one person per name, so the
            // counts below mean something.
            runCatching { referenceRepository.findOrCreatePerson(userId, name) }
                .onFailure { MmLog.e("Could not add the person", it) }
            drafts.value = drafts.value.copy(draftName = "")
        }
    }

    fun addPlace() {
        val name = drafts.value.draftName.trim()
        val location = drafts.value.draftLocation
        val userId = authRepository.currentUserId.value
        // A place without coordinates cannot be shown on the map, so it is not
        // offered as a place at all.
        if (name.isEmpty() || location == null || userId == null) return
        viewModelScope.launch {
            runCatching {
                referenceRepository.savePlace(Place(userId = userId, name = name, location = location))
            }.onFailure { MmLog.e("Could not add the place", it) }
            drafts.value = drafts.value.copy(draftName = "", draftLocation = null)
        }
    }

    fun deletePerson(id: String) {
        viewModelScope.launch {
            runCatching { referenceRepository.deletePerson(id) }
                .onFailure { MmLog.e("Could not delete the person", it) }
        }
    }

    fun deletePlace(id: String) {
        viewModelScope.launch {
            runCatching { referenceRepository.deletePlace(id) }
                .onFailure { MmLog.e("Could not delete the place", it) }
        }
    }

    private data class Lists(
        val people: List<Person>,
        val places: List<Place>,
        val personCounts: Map<String, Int>,
        val placeCounts: Map<String, Int>,
    )

    /**
     * Counts are read here rather than in the list, because the list would
     * otherwise fire two queries per visible row on every recomposition.
     */
    private fun listsFor(userId: String): Flow<Lists> = combine(
        referenceRepository.watchPeople(userId),
        referenceRepository.watchPlaces(userId),
    ) { people, places -> people to places }
        .map { (people, places) ->
            Lists(
                people = people,
                places = places,
                personCounts = people.associate { it.id to countForPerson(it.id) },
                placeCounts = places.associate { it.id to countAtPlace(it.id) },
            )
        }

    private suspend fun countForPerson(personId: String): Int =
        referenceRepository.entriesWithPerson(personId).size +
            referenceRepository.memoriesWithPerson(personId).size

    private suspend fun countAtPlace(placeId: String): Int =
        referenceRepository.entriesAtPlace(placeId).size +
            referenceRepository.memoriesAtPlace(placeId).size
}
