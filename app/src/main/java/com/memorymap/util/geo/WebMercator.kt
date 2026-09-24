package com.memorymap.util.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Web Mercator (EPSG:3857) projection maths for a slippy tile map.
 *
 * This is the same scheme OpenStreetMap raster tiles use, so the app can draw
 * tiles, markers and a picker pin without depending on any map SDK. Pure and
 * allocation-free on purpose: it is tested on the JVM and called on every frame
 * of a pan or a zoom.
 */
object WebMercator {

    /** OSM raster tiles are 256 px. */
    const val TILE_SIZE = 256

    /**
     * The latitude a Mercator map can actually show. Beyond it the projection
     * goes to infinity, which is why every world map is cut off at the poles.
     */
    const val MAX_LATITUDE = 85.0511287798

    /** Width or height of the whole world at [zoom], in pixels. */
    fun worldSize(zoom: Int): Double = TILE_SIZE * 2.0.pow(zoom)

    /** Clamps a latitude into the range a Mercator map can render. */
    fun clampLatitude(latitude: Double): Double =
        latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)

    /** Wraps a longitude into `-180..180`, so panning past the antimeridian works. */
    fun wrapLongitude(longitude: Double): Double {
        var lon = longitude
        while (lon > 180.0) lon -= 360.0
        while (lon < -180.0) lon += 360.0
        return lon
    }

    /** Longitude to a horizontal world-pixel coordinate at [zoom]. */
    fun longitudeToX(longitude: Double, zoom: Int): Double {
        val world = worldSize(zoom)
        return (wrapLongitude(longitude) + 180.0) / 360.0 * world
    }

    /** Latitude to a vertical world-pixel coordinate at [zoom]. */
    fun latitudeToY(latitude: Double, zoom: Int): Double {
        val world = worldSize(zoom)
        val lat = Math.toRadians(clampLatitude(latitude))
        val mercator = ln(tan(PI / 4) * (1 + sin(lat)) / cos(lat))
        return world / 2.0 - world / (2.0 * PI) * mercator
    }

    /** Horizontal world pixel back to longitude. */
    fun xToLongitude(x: Double, zoom: Int): Double =
        wrapLongitude(x / worldSize(zoom) * 360.0 - 180.0)

    /** Vertical world pixel back to latitude. */
    fun yToLatitude(y: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * y / worldSize(zoom)
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Column index of the tile containing [longitude]. Wrapped, never negative. */
    fun tileX(longitude: Double, zoom: Int): Int {
        val count = 1 shl zoom
        val x = floor((wrapLongitude(longitude) + 180.0) / 360.0 * count).toInt()
        return ((x % count) + count) % count
    }

    /** Row index of the tile containing [latitude], clamped to the valid range. */
    fun tileY(latitude: Double, zoom: Int): Int {
        val count = 1 shl zoom
        val lat = Math.toRadians(clampLatitude(latitude))
        val y = floor((1.0 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2.0 * count).toInt()
        return y.coerceIn(0, count - 1)
    }

    /** How many metres one screen pixel covers at [latitude] and [zoom]. */
    fun metersPerPixel(latitude: Double, zoom: Int): Double =
        156_543.03392 * cos(Math.toRadians(clampLatitude(latitude))) / 2.0.pow(zoom)

    /**
     * True when [y] is a real tile row at [zoom]. Columns are not checked
     * because the map wraps horizontally: any [x] has a valid tile.
     */
    fun isValidTileY(zoom: Int, y: Int): Boolean = y in 0 until (1 shl zoom)
}
