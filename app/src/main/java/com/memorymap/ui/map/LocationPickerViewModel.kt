package com.memorymap.ui.map

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.map.MapProvider
import com.memorymap.domain.model.GeoPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** UI state of the manual location picker. */
data class LocationPickerUiState(
    val center: GeoPoint,
    val zoom: Double = 15.0,
)

/**
 * Backs the manual location picker.
 *
 * The pin is always the centre of the map, so "pick a location" is just "move the
 * map": the same gesture the user already knows, and no second way of placing a
 * pin to get wrong.
 */
@HiltViewModel
class LocationPickerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val provider: MapProvider,
) : ViewModel() {

    private val initial: GeoPoint = run {
        val lat = savedStateHandle.get<String>("lat")?.toDoubleOrNull()
        val lon = savedStateHandle.get<String>("lon")?.toDoubleOrNull()
        if (lat != null && lon != null) {
            GeoPoint(lat, lon)
        } else {
            MapViewModel.DEFAULT_CENTER
        }
    }

    /**
     * Opened on a place, the map starts where the street is visible; opened with no
     * place, it starts wide enough to find one.
     *
     * Held as a value rather than recomputed inside the flow, because it is also the
     * zoom of the state the screen shows **before** anything is collected: an initial
     * state carrying the data class's default would draw one frame at the wrong
     * distance and then jump.
     */
    private val startZoom = if (initial == MapViewModel.DEFAULT_CENTER) START_WIDE else START_CLOSE

    private val center = MutableStateFlow(initial)
    private val zoom = MutableStateFlow(startZoom)

    val state: StateFlow<LocationPickerUiState> = combine(center, zoom) { c, z ->
        LocationPickerUiState(center = c, zoom = z)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LocationPickerUiState(center = initial, zoom = startZoom),
    )

    /** The chosen point: wherever the map is centred right now. */
    val picked: GeoPoint get() = center.value

    fun onCenterChange(value: GeoPoint) {
        center.value = value
    }

    fun onZoomChange(value: Double) {
        zoom.value = value.coerceIn(provider.minZoom.toDouble(), provider.maxZoom.toDouble())
    }

    private companion object {
        /** Zoom for a picker that was given no place: the whole country, roughly. */
        const val START_WIDE = 3.0

        /** Zoom for a picker opened on a place: a street. */
        const val START_CLOSE = 15.0
    }
}
