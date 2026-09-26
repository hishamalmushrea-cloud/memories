package com.memorymap.domain.usecase

import com.memorymap.util.geo.WebMercator
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

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
 * the same grid square merges into one pin at the group's average position. The
 * square is measured in screen pixels, not degrees, so the same group breaks
 * apart as you zoom in — the behaviour a user expects from a map.
 *
 * There is no "too few markers to bother" special case: the grid decides
 * everything, so two pins far apart stay two pins no matter how empty the map is.
 *
 * Pure, so the merging rules are tested on the JVM.
 */
object MapClustering {

    /**
     * The side of a grouping square, in screen pixels.
     *
     * Roughly the width of a cluster bubble, so two markers that would overlap on
     * screen merge and two that would not are left alone.
     */
    const val CLUSTER_CELL_PIXELS = 64.0

    fun cluster(
        markers: List<MapMarker>,
        zoom: Int,
        cellPixels: Double = CLUSTER_CELL_PIXELS,
    ): List<MapCluster> {
        if (markers.isEmpty()) return emptyList()
        if (markers.size == 1) return listOf(asCluster(markers))

        val cells = ceil(WebMercator.worldSize(zoom) / cellPixels).toInt().coerceAtLeast(1)
        val cellWidth = WebMercator.worldSize(zoom).toDouble() / cells

        // Keyed by cell, so the order of the input cannot change the result.
        val buckets = LinkedHashMap<Long, MutableList<MapMarker>>()
        markers.forEach { marker ->
            val x = WebMercator.longitudeToX(marker.longitude, zoom)
            val y = WebMercator.latitudeToY(marker.latitude, zoom)
            val key = cellKey(floor(x / cellWidth).toLong(), floor(y / cellWidth).toLong())
            buckets.getOrPut(key) { mutableListOf() }.add(marker)
        }

        return buckets.values.map(::asCluster)
    }

    private fun asCluster(group: List<MapMarker>): MapCluster = MapCluster(
        latitude = group.sumOf { it.latitude } / group.size,
        longitude = averageLongitude(group),
        markers = group,
    )

    /**
     * Averages longitudes, which are circular.
     *
     * A plain mean of `179` and `-179` is `0`, on the other side of the planet.
     * Averaging the unit vectors and converting back is correct for every group,
     * including one that happens to straddle the date line.
     */
    private fun averageLongitude(group: List<MapMarker>): Double {
        var x = 0.0
        var y = 0.0
        group.forEach { marker ->
            val radians = Math.toRadians(marker.longitude)
            x += cos(radians)
            y += sin(radians)
        }
        return Math.toDegrees(atan2(y, x))
    }

    /** Packs two cell indices into one key. */
    private fun cellKey(x: Long, y: Long): Long = (x shl 32) or (y and 0xFFFFFFFFL)
}
