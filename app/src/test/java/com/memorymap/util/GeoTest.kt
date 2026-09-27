package com.memorymap.util

import com.memorymap.domain.model.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    // Sanaa and Ibb, roughly 120 km apart on a north-south line.
    private val sanaa = GeoPoint(15.3694, 44.1910)
    private val ibb = GeoPoint(13.9667, 44.1667)

    @Test
    fun `distance to the same point is zero`() {
        assertEquals(0.0, Geo.distanceMeters(sanaa, sanaa), 0.001)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val a = GeoPoint(0.0, 0.0)
        val b = GeoPoint(1.0, 0.0)
        val distance = Geo.distanceMeters(a, b)
        assertTrue("expected ~111 km, got $distance", distance in 110_000.0..112_000.0)
    }

    @Test
    fun `distance is symmetric`() {
        val forward = Geo.distanceMeters(sanaa, ibb)
        val backward = Geo.distanceMeters(ibb, sanaa)
        assertEquals(forward, backward, 0.001)
    }

    @Test
    fun `sanaa to ibb is roughly the expected range`() {
        val distance = Geo.distanceMeters(sanaa, ibb)
        assertTrue("expected ~155 km, got $distance", distance in 150_000.0..160_000.0)
    }

    @Test
    fun `within one kilometre keeps only close points`() {
        val origin = GeoPoint(15.3694, 44.1910)
        val near = GeoPoint(15.3700, 44.1915) // tens of metres away
        val far = ibb

        assertTrue(Geo.isWithin(origin, near))
        assertFalse(Geo.isWithin(origin, far))

        val results = Geo.nearby(origin, listOf(near, far), locationOf = { it })
        assertEquals(1, results.size)
        assertEquals(near, results.first().first)
    }

    @Test
    fun `items without a location are skipped, not treated as nearby`() {
        val origin = GeoPoint(15.3694, 44.1910)
        val results = Geo.nearby(origin, listOf<String>("no-location"), locationOf = { null })
        assertTrue(results.isEmpty())
    }

    @Test
    fun `nearby results are ordered closest first`() {
        val origin = GeoPoint(0.0, 0.0)
        val mid = GeoPoint(0.002, 0.0)
        val closest = GeoPoint(0.001, 0.0)
        val results = Geo.nearby(origin, listOf(mid, closest), radiusMeters = 5_000.0, locationOf = { it })
        assertEquals(closest, results[0].first)
        assertEquals(mid, results[1].first)
    }

    @Test
    fun `coordinate validation rejects impossible values`() {
        assertTrue(Geo.isValid(0.0, 0.0))
        assertTrue(Geo.isValid(-90.0, 180.0))
        assertFalse(Geo.isValid(91.0, 0.0))
        assertFalse(Geo.isValid(0.0, -181.0))
    }

    @Test
    fun `distance formatting switches unit at one kilometre`() {
        val locale = java.util.Locale.ENGLISH
        assertEquals("850 m", Geo.formatDistance(850.0, locale))
        assertEquals("1.5 km", Geo.formatDistance(1_500.0, locale))
    }
}
