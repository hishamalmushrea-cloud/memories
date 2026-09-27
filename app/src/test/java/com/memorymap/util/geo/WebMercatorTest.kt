package com.memorymap.util.geo

import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection the map, the pins and the location picker all depend on.
 *
 * If these are wrong the map still draws, but every pin lands in the wrong place,
 * so the round trips matter more than any single value.
 */
class WebMercatorTest {

    private val EPSILON = 1e-6

    @Test
    fun `the world doubles in pixels with every zoom level`() {
        assertEquals(256.0, WebMercator.worldSize(0), EPSILON)
        assertEquals(512.0, WebMercator.worldSize(1), EPSILON)
        assertEquals(256.0 * 2.0.pow(10), WebMercator.worldSize(10), EPSILON)
    }

    @Test
    fun `the centre of the world is the prime meridian and the equator`() {
        assertEquals(WebMercator.worldSize(3) / 2, WebMercator.longitudeToX(0.0, 3), EPSILON)
        assertEquals(WebMercator.worldSize(3) / 2, WebMercator.latitudeToY(0.0, 3), EPSILON)
    }

    @Test
    fun `the map edges are the antimeridian and the mercator limit`() {
        assertEquals(0.0, WebMercator.longitudeToX(-180.0, 2), EPSILON)
        assertEquals(WebMercator.worldSize(2), WebMercator.longitudeToX(180.0, 2), EPSILON)
        assertEquals(0.0, WebMercator.latitudeToY(WebMercator.MAX_LATITUDE, 2), EPSILON)
        assertEquals(WebMercator.worldSize(2), WebMercator.latitudeToY(-WebMercator.MAX_LATITUDE, 2), EPSILON)
    }

    @Test
    fun `longitude survives a round trip through pixels`() {
        listOf(-179.5, -90.0, -0.25, 0.0, 44.19, 120.0, 179.9).forEach { lon ->
            val x = WebMercator.longitudeToX(lon, 12)
            assertEquals(lon, WebMercator.xToLongitude(x, 12), 1e-9)
        }
    }

    @Test
    fun `latitude survives a round trip through pixels`() {
        listOf(-85.0, -45.0, -0.5, 0.0, 15.35, 60.0, 84.0).forEach { lat ->
            val y = WebMercator.latitudeToY(lat, 12)
            assertEquals(lat, WebMercator.yToLatitude(y, 12), 1e-9)
        }
    }

    @Test
    fun `columns wrap so panning past the antimeridian still finds tiles`() {
        assertEquals(0, WebMercator.tileX(-180.0, 1))
        assertEquals(1, WebMercator.tileX(0.0, 1))
        assertEquals(WebMercator.tileX(-170.0, 3), WebMercator.tileX(190.0, 3))
        assertEquals(WebMercator.tileX(10.0, 4), WebMercator.tileX(10.0 - 720.0, 4))
    }

    @Test
    fun `rows clamp at the poles instead of falling off the map`() {
        assertEquals(0, WebMercator.tileY(90.0, 2))
        assertEquals(3, WebMercator.tileY(-90.0, 2))
        assertEquals(0, WebMercator.tileY(WebMercator.MAX_LATITUDE + 5, 2))
    }

    @Test
    fun `latitudes past the mercator limit are clamped, not projected to infinity`() {
        assertEquals(WebMercator.MAX_LATITUDE, WebMercator.clampLatitude(89.0), EPSILON)
        assertEquals(-WebMercator.MAX_LATITUDE, WebMercator.clampLatitude(-89.0), EPSILON)
        assertEquals(30.0, WebMercator.clampLatitude(30.0), EPSILON)
    }

    @Test
    fun `longitudes are wrapped into range`() {
        assertEquals(-170.0, WebMercator.wrapLongitude(190.0), EPSILON)
        assertEquals(170.0, WebMercator.wrapLongitude(-190.0), EPSILON)
        assertEquals(15.0, WebMercator.wrapLongitude(15.0), EPSILON)
    }

    @Test
    fun `a pixel covers less ground the further you zoom in`() {
        val far = WebMercator.metersPerPixel(0.0, 3)
        val near = WebMercator.metersPerPixel(0.0, 13)
        assertTrue(far > near)
        assertEquals(far / 2.0.pow(10), near, 1e-6)
    }

    @Test
    fun `a pixel covers less ground the closer you are to a pole`() {
        val equator = WebMercator.metersPerPixel(0.0, 8)
        val north = WebMercator.metersPerPixel(60.0, 8)
        assertTrue(north < equator)
    }

    @Test
    fun `tile row validity follows the zoom level`() {
        assertTrue(WebMercator.isValidTileY(2, 0))
        assertTrue(WebMercator.isValidTileY(2, 3))
        assertFalse(WebMercator.isValidTileY(2, 4))
        assertFalse(WebMercator.isValidTileY(2, -1))
    }

    @Test
    fun `a tile index always belongs to its own zoom level`() {
        for (zoom in 0..14) {
            val count = 1 shl zoom
            for (lon in listOf(-179.0, -1.0, 0.0, 45.0, 179.0)) {
                val x = WebMercator.tileX(lon, zoom)
                assertTrue("x=$x at zoom=$zoom", x in 0 until count)
            }
            for (lat in listOf(-85.0, 0.0, 85.0)) {
                val y = WebMercator.tileY(lat, zoom)
                assertTrue("y=$y at zoom=$zoom", y in 0 until count)
            }
        }
        // Guards against a silently wrong scale somewhere.
        assertTrue(abs(WebMercator.longitudeToX(45.0, 7) / WebMercator.worldSize(7) - 0.625) < 1e-9)
    }
}
