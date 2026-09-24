package com.memorymap.domain.usecase

import com.memorymap.util.geo.WebMercator
import kotlin.math.floor

/** One thing that can be pinned to the map: a memory or a located event. */
data class MapMarker(
    val id: String,
    val title: String,
    val latitude: Double,
    val longitude: Double,
    val isMemory: Boolean,
    /** The day an event belongs to, which its editor route needs. Null for memories. */
    val dateIso: String? = null,
)

/**
 * One pin on the map. A cluster of one opens the record; a cluster of many zooms
 * in, which is what keeps a city full of memories readable.
 */
data class MapCluster(
    val latitude: Double,
    val longitude: Double,
    val markers: List<MapMarker>,
) {
    val size: Int get() = markers.size
    val isSingle: Boolean get() = markers.size == 1
}

/**
 * Grid clustering in screen space.
 *
 * Markers are projected to pixels at the current zoom and everything landing in
 * the same grid cell merges into one pin at the group's average position. The
 * grid is fixed in screen pixels rather than in degrees, so the same cluster
 * breaks apart as you zoom in — which is the behaviour a user expects from a
 * map.
 *
 * Pure, so the merging rules are tested on the JVM.
 */
object MapClustering {

    /** Cells per axis. Smaller means fewer, fatter clusters. */
    const val DEFAULT_GRID_CELLS = 24

    /**
     * At or below this many markers clustering is not worth doing: every pin is
     * returned on its own, so a sparse map never hides anything behind a bubble.
     */
    const val CLUSTERING_THRESHOLD = 3

    fun cluster(
        markers: List<MapMarker>,
        zoom: Int,
        gridCells: Int = DEFAULT_GRID_CELLS,
    ): List<MapCluster> {
        if (markers.size <= CLUSTERING_THRESHOLD || gridCells <= 0) {
            return markers.map { MapCluster(it.latitude, it.longitude, listOf(it)) }
        }

        val world = WebMercator.worldSize(zoom)
        val cell = world / gridCells

        // Keyed by cell so the order of the input cannot change the result.
        val buckets = LinkedHashMap<Long, MutableList<MapMarker>>()
        markers.forEach { marker ->
            val x = WebMercator.longitudeToX(marker.longitude, zoom)
            val y = WebMercator.latitudeToY(marker.latitude, zoom)
            val key = cellKey(floor(x / cell).toLong(), floor(y / cell).toLong())
            buckets.getOrPut(key) { mutableListOf() }.add(marker)
        }

        return buckets.values.map { group ->
            MapCluster(
                latitude = group.sumOf { it.latitude } / group.size,
                longitude = averageLongitude(group),
                markers = group,
            )
        }
    }

    /**
     * Averages longitudes across the antimeridian.
     *
     * A plain mean of `179` and `-179` is `0`, which is on the other side of the
     * planet. Averaging the unit vectors and converting back puts the cluster
     * where the markers actually are.
     */
    private fun averageLongitude(group: List<MapMarker>): Double {
        var x = 0.0
        var y = 0.0
        group.forEach { marker ->
            val radians = Math.toRadians(marker.longitude)
            x += kotlin.math.cos(radians)
            y += kotlin.math.sin(radians)
        }
        return Math.toDegrees(kotlin.math.atan2(y, x))
    }

    /** Packs two cell indices into one key; `x` is wrapped so the seam merges. */
    private fun cellKey(x: Long, y: Long): Long = (x shl 32) or (y and 0xFFFFFFFFL)
}
