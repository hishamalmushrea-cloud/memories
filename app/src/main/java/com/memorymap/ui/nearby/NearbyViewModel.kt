package com.memorymap.ui.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.domain.repository.MemoryRepository
import com.memorymap.util.Geo
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** One memory or event within reach, with how far away it is. */
data class NearbyItem(
    val id: String,
    val title: String,
    val isMemory: Boolean,
    /** The day it belongs to, which the event editor route needs. */
    val dateIso: String,
    val distanceMeters: Double,
)

/** UI state of "near by": what is inside the radius, closest first. */
data class NearbyUiState(
    val origin: GeoPoint? = null,
    val radiusMeters: Double = Geo.DEFAULT_NEARBY_RADIUS_M,
    val items: List<NearbyItem> = emptyList(),
    /** How many records carry a location at all, so an empty radius can be explained. */
    val locatedCount: Int = 0,
    val locationFailed: Boolean = false,
    val isLoading: Boolean = true,
)

/**
 * "Near by": the memories and events inside one kilometre of a position the user
 * asked for.
 *
 * The position is never read here and never watched: the screen reads it once,
 * when the button is pressed, and hands it over. Until then [NearbyUiState.origin]
 * is null and nothing is filtered, so the screen can say plainly that no position
 * has been read yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NearbyViewModel @Inject constructor(
    memoryRepository: MemoryRepository,
    diaryRepository: DiaryRepository,
    authRepository: AuthRepository,
) : ViewModel() {

    private val origin = MutableStateFlow<GeoPoint?>(null)
    private val locationFailed = MutableStateFlow(false)

    /** Every memory and event of the account that carries coordinates. */
    private val located: Flow<List<LocatedRecord>> = authRepository.currentUserId
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(emptyList())
            } else {
                combine(
                    memoryRepository.watchLocated(userId),
                    diaryRepository.watchLocated(userId),
                ) { memories, entries ->
                    memories.mapNotNull { memory ->
                        memory.location?.let { point ->
                            LocatedRecord(
                                id = memory.id,
                                title = memory.title,
                                isMemory = true,
                                dateIso = memory.memoryDate.toString(),
                                point = point,
                            )
                        }
                    } + entries.mapNotNull { entry ->
                        entry.location?.let { point ->
                            LocatedRecord(
                                id = entry.id,
                                title = entry.title,
                                isMemory = false,
                                dateIso = entry.date.toString(),
                                point = point,
                            )
                        }
                    }
                }
            }
        }
        .catch { failure ->
            MmLog.e("Unable to load the records that carry a location", failure)
            emit(emptyList())
        }

    val state: StateFlow<NearbyUiState> = combine(
        origin,
        located,
        locationFailed,
    ) { originPoint, records, failed ->
        val within = originPoint?.let { point ->
            Geo.nearby(point, records, Geo.DEFAULT_NEARBY_RADIUS_M) { record -> record.point }
        }.orEmpty()

        NearbyUiState(
            origin = originPoint,
            items = within.map { (record, distance) ->
                NearbyItem(
                    id = record.id,
                    title = record.title,
                    isMemory = record.isMemory,
                    dateIso = record.dateIso,
                    distanceMeters = distance,
                )
            },
            locatedCount = records.size,
            locationFailed = failed,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyUiState())

    /** The position the user just asked for, read once by the screen. */
    fun onLocated(point: GeoPoint) {
        locationFailed.value = false
        origin.value = point
    }

    /** The position could not be read; the screen shows the reason it was given. */
    fun onLocationFailed() {
        locationFailed.value = true
    }

    fun clearOrigin() {
        origin.value = null
    }
}

/** A memory or an event that carries coordinates, in the shape the radius filter wants. */
private data class LocatedRecord(
    val id: String,
    val title: String,
    val isMemory: Boolean,
    val dateIso: String,
    val point: GeoPoint,
)
