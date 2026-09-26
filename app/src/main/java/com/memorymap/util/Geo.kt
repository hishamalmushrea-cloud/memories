package com.memorymap.util

import com.memorymap.domain.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geo helpers for "nearby" and for sorting map markers.
 *
 * Location is only ever read when the user asks for it (opening the map, saving
 * a memory, attaching a place to an event). There is no background tracking.
 */
object Geo {

    /** Mean earth radius in metres (WGS-84 mean). */
    private const val EARTH_RADIUS_M = 6_371_008.8

    /** Default "nearby" radius from the spec. */
    const val DEFAULT_NEARBY_RADIUS_M = 1_000.0

    /** Great-circle distance in metres between two coordinates. */
    fun distanceMeters(a: GeoPoint, b: GeoPoint): Double =
        distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)

    /** Great-circle distance in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** True when [point] is inside [radiusMeters] of [origin]. */
    fun isWithin(origin: GeoPoint, point: GeoPoint, radiusMeters: Double = DEFAULT_NEARBY_RADIUS_M): Boolean =
        distanceMeters(origin, point) <= radiusMeters

    /** Keeps only the points inside [radiusMeters], closest first. */
    fun <T> nearby(
        origin: GeoPoint,
        items: List<T>,
        radiusMeters: Double = DEFAULT_NEARBY_RADIUS_M,
        locationOf: (T) -> GeoPoint?,
    ): List<Pair<T, Double>> = items
        .mapNotNull { item -> locationOf(item)?.let { item to distanceMeters(origin, it) } }
        .filter { (_, distance) -> distance <= radiusMeters }
        .sortedBy { (_, distance) -> distance }

    /** True for coordinates that are actually on the planet. */
    fun isValid(latitude: Double, longitude: Double): Boolean =
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    /** Human readable distance, metres below 1 km and kilometres above it. */
    fun formatDistance(meters: Double, locale: java.util.Locale = java.util.Locale.getDefault()): String =
        if (meters < 1000) {
            String.format(locale, "%.0f m", meters)
        } else {
            String.format(locale, "%.1f km", meters / 1000.0)
        }
}
