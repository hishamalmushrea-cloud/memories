package com.memorymap.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.map.MapProvider
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.domain.repository.MemoryRepository
import com.memorymap.domain.usecase.MapCluster
import com.memorymap.domain.usecase.MapClustering
import com.memorymap.domain.usecase.MapMarker
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI state of the map. */
data class MapUiState(
    val center: GeoPoint = MapViewModel.DEFAULT_CENTER,
    val zoom: Double = MapViewModel.DEFAULT_ZOOM,
    val clusters: List<MapCluster> = emptyList(),
    val userLocation: GeoPoint? = null,
    val markerCount: Int = 0,
    val isLoading: Boolean = true,
)

/**
 * The home screen: every memory and every located event on one map.
 *
 * Clustering runs on the current zoom, so a dense city collapses into countable
 * pins and opens up as you zoom in. The camera starts wide; it moves to the
 * user's own content the first time there is any, and to their position when they
 * ask for it.
 */
@HiltViewModel
class MapViewModel @Inject constructor(
    memoryRepository: MemoryRepository,
    diaryRepository: DiaryRepository,
    authRepository: AuthRepository,
    val provider: MapProvider,
) : ViewModel() {

    private val markers = MutableStateFlow<List<MapMarker>>(emptyList())
    private val center = MutableStateFlow(DEFAULT_CENTER)
    private val zoom = MutableStateFlow(DEFAULT_ZOOM)
    private val userLocation = MutableStateFlow<GeoPoint?>(null)
    private var cameraMovedToContent = false

    val state: StateFlow<MapUiState> = combine(
        center,
        zoom,
        markers,
        userLocation,
    ) { currentCenter, currentZoom, currentMarkers, location ->
        MapUiState(
            center = currentCenter,
            zoom = currentZoom,
            clusters = MapClustering.cluster(currentMarkers, currentZoom.toInt()),
            userLocation = location,
            markerCount = currentMarkers.size,
            isLoading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    init {
        viewModelScope.launch {
            authRepository.currentUserId.collect { userId ->
                if (userId == null) return@collect
                runCatching {
                    combine(
                        memoryRepository.watchLocated(userId),
                        diaryRepository.watchLocated(userId),
                    ) { memories, entries ->
                        memories.mapNotNull { memory ->
                            memory.location?.let {
                                MapMarker(memory.id, memory.title, it.latitude, it.longitude, true)
                            }
                        } + entries.mapNotNull { entry ->
                            entry.location?.let {
                                MapMarker(
                                    id = entry.id,
                                    title = entry.title,
                                    latitude = it.latitude,
                                    longitude = it.longitude,
                                    isMemory = false,
                                    dateIso = entry.date.toString(),
                                )
                            }
                        }
                    }.collect { list ->
                        markers.value = list
                        // Centre on the user's own content once, and never again
                        // after that: fighting the user for the camera is worse
                        // than an empty map.
                        if (!cameraMovedToContent && list.isNotEmpty()) {
                            cameraMovedToContent = true
                            center.value = GeoPoint(list.first().latitude, list.first().longitude)
                            zoom.value = CONTENT_ZOOM
                        }
                    }
                }.onFailure { MmLog.e("Unable to load the map markers", it) }
            }
        }
    }

    fun onCenterChange(value: GeoPoint) {
        center.value = value
    }

    fun onZoomChange(value: Double) {
        zoom.value = value.coerceIn(provider.minZoom.toDouble(), provider.maxZoom.toDouble())
    }

    /** Zooms into a cluster; the screen opens the record when it holds one item. */
    fun zoomTo(cluster: MapCluster) {
        center.value = GeoPoint(cluster.latitude, cluster.longitude)
        zoom.value = (zoom.value + 2).coerceAtMost(provider.maxZoom.toDouble())
    }

    /** Remembers a position the user just picked, so the pin stays visible. */
    fun onUserLocation(value: GeoPoint) {
        userLocation.value = value
        center.value = value
        if (zoom.value < CONTENT_ZOOM) zoom.value = CONTENT_ZOOM
    }

    fun clearUserLocation() {
        userLocation.value = null
    }

    companion object {
        /**
         * An empty archive shows the whole world rather than a guess at where the
         * user lives. It moves to their content as soon as there is any.
         */
        val DEFAULT_CENTER = GeoPoint(latitude = 20.0, longitude = 0.0)
        const val DEFAULT_ZOOM = 2.0
        const val CONTENT_ZOOM = 11.0
    }
}
