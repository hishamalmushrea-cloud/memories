package com.memorymap.domain.usecase

import com.memorymap.util.geo.WebMercator
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Marker clustering.
 *
 * The rules a user feels: a sparse map shows every pin, a dense city collapses
 * into countable bubbles, and zooming in breaks a bubble apart.
 */
class MapClusteringTest {

    @Test
    fun `a sparse map shows every pin on its own`() {
        val markers = listOf(
            marker("a", 15.35, 44.20),
            marker("b", 12.78, 45.02),
            marker("c", 13.58, 43.22),
        )

        val clusters = MapClustering.cluster(markers, zoom = 5)

        assertEquals(3, clusters.size)
        assertTrue(clusters.all { it.isSingle })
    }

    @Test
    fun `pins in the same screen cell merge into one cluster`() {
        // Four points inside a few hundred metres: one bubble at any city zoom.
        val markers = listOf(
            marker("a", 15.3500, 44.2000),
            marker("b", 15.3501, 44.2001),
            marker("c", 15.3502, 44.1999),
            marker("d", 15.3499, 44.2002),
        )

        val clusters = MapClustering.cluster(markers, zoom = 10, gridCells = 8)

        assertEquals(1, clusters.size)
        assertEquals(4, clusters.single().size)
    }

    @Test
    fun `zooming in breaks a cluster apart`() {
        val markers = listOf(
            marker("a", 15.3500, 44.2000),
            marker("b", 15.3600, 44.2200),
            marker("c", 15.4000, 44.3000),
            marker("d", 15.5000, 44.5000),
        )

        val merged = MapClustering.cluster(markers, zoom = 5, gridCells = 4)
        val split = MapClustering.cluster(markers, zoom = 14, gridCells = 24)

        assertTrue("far zoom must merge", merged.size < markers.size)
        assertEquals("close zoom must show all four", markers.size, split.size)
    }

    @Test
    fun `a cluster sits at the average of its markers`() {
        val markers = listOf(
            marker("a", 10.0, 20.0),
            marker("b", 10.2, 20.2),
        )

        val cluster = MapClustering.cluster(markers, zoom = 8, gridCells = 2).single()

        assertEquals(10.1, cluster.latitude, 1e-9)
        assertEquals(20.1, cluster.longitude, 1e-9)
        assertEquals(2, cluster.size)
    }

    @Test
    fun `markers across the antimeridian cluster on the date line, not at greenwich`() {
        val markers = listOf(
            marker("a", 0.0, 179.9),
            marker("b", 0.0, -179.9),
        )

        val cluster = MapClustering.cluster(markers, zoom = 4, gridCells = 4).single()

        // A plain mean would put this at 0.0, on the other side of the planet.
        assertTrue(
            "expected a longitude near 180, got ${cluster.longitude}",
            abs(abs(cluster.longitude) - 180.0) < 1.0,
        )
    }

    @Test
    fun `the result does not depend on the order of the input`() {
        val markers = (0 until 30).map { marker("m$it", 15.0 + it * 0.01, 44.0 + it * 0.013) }

        val forward = MapClustering.cluster(markers, zoom = 9).map { it.size }.sorted()
        val backward = MapClustering.cluster(markers.reversed(), zoom = 9).map { it.size }.sorted()

        assertEquals(forward, backward)
    }

    @Test
    fun `an empty map produces no clusters`() {
        assertTrue(MapClustering.cluster(emptyList(), zoom = 10).isEmpty())
    }

    @Test
    fun `every marker survives clustering exactly once`() {
        val markers = (0 until 50).map { marker("m$it", 10.0 + it * 0.05, 40.0 + it * 0.07) }

        for (zoom in 3..16) {
            val clustered = MapClustering.cluster(markers, zoom)
            assertEquals(
                "zoom=$zoom lost or duplicated a marker",
                markers.size,
                clustered.sumOf { it.size },
            )
            assertEquals(markers.map { it.id }.toSet(), clustered.flatMap { c -> c.markers.map { it.id } }.toSet())
        }
    }

    @Test
    fun `a cluster of one is single and can be opened directly`() {
        val cluster = MapClustering.cluster(listOf(marker("only", 1.0, 2.0)), zoom = 10).single()
        assertTrue(cluster.isSingle)
        assertEquals("only", cluster.markers.single().id)
    }

    @Test
    fun `markers keep the day an event belongs to`() {
        val marker = MapMarker("e1", "حدث", 15.0, 44.0, isMemory = false, dateIso = "2026-09-23")
        val cluster = MapClustering.cluster(listOf(marker), zoom = 10).single()
        assertEquals("2026-09-23", cluster.markers.single().dateIso)
        // Sanity: the projection behind the grid is the same one the map draws with.
        assertTrue(WebMercator.isValidTileY(10, WebMercator.tileY(15.0, 10)))
    }

    private fun marker(id: String, latitude: Double, longitude: Double) =
        MapMarker(id = id, title = id, latitude = latitude, longitude = longitude, isMemory = true)
}
