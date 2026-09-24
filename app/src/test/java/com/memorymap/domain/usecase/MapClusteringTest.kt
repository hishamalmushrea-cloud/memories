package com.memorymap.domain.usecase

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
        // Three Yemeni cities: hundreds of kilometres apart.
        val markers = listOf(
            marker("sanaa", 15.35, 44.20),
            marker("aden", 12.78, 45.02),
            marker("taiz", 13.58, 43.22),
        )

        val clusters = MapClustering.cluster(markers, zoom = 5)

        assertEquals(3, clusters.size)
        assertTrue(clusters.all { it.isSingle })
    }

    @Test
    fun `pins inside one screen square merge into one cluster`() {
        // Four points a few tens of metres apart: one bubble at city zoom.
        val markers = listOf(
            marker("a", 15.3500, 44.2500),
            marker("b", 15.3501, 44.2501),
            marker("c", 15.3502, 44.2499),
            marker("d", 15.3499, 44.2502),
        )

        val clusters = MapClustering.cluster(markers, zoom = 10)

        assertEquals(1, clusters.size)
        assertEquals(4, clusters.single().size)
    }

    @Test
    fun `zooming in breaks a cluster apart`() {
        val markers = listOf(
            marker("a", 15.35, 44.20),
            marker("b", 15.36, 44.22),
            marker("c", 15.40, 44.30),
            marker("d", 15.50, 44.50),
        )

        val merged = MapClustering.cluster(markers, zoom = 5)
        val split = MapClustering.cluster(markers, zoom = 14)

        assertEquals("far zoom must merge", 1, merged.size)
        assertEquals("close zoom must show all four", markers.size, split.size)
    }

    @Test
    fun `a cluster sits at the average of its markers`() {
        val markers = listOf(
            marker("a", 10.0, 20.0),
            marker("b", 10.2, 20.2),
        )

        val cluster = MapClustering.cluster(markers, zoom = 1).single()

        assertEquals(2, cluster.size)
        assertEquals(10.1, cluster.latitude, 1e-9)
        assertEquals(20.1, cluster.longitude, 1e-9)
    }

    @Test
    fun `two pins are never hidden just because the map is nearly empty`() {
        // A "too few markers to bother" shortcut would fail this, and a user with
        // three memories would wonder where one of them went.
        val markers = listOf(
            marker("a", 15.3500, 44.2500),
            marker("b", 15.3501, 44.2501),
        )

        val clusters = MapClustering.cluster(markers, zoom = 10)

        assertEquals(1, clusters.size)
        assertEquals(2, clusters.single().size)
    }

    @Test
    fun `clustered positions stay inside valid coordinates`() {
        val markers = listOf(
            marker("east", 0.0, 179.99),
            marker("west", 0.0, -179.99),
            marker("pole", 84.0, 0.0),
        )

        val clusters = MapClustering.cluster(markers, zoom = 3)

        assertEquals(markers.size, clusters.sumOf { it.size })
        clusters.forEach {
            assertTrue("latitude out of range: ${it.latitude}", it.latitude in -90.0..90.0)
            assertTrue("longitude out of range: ${it.longitude}", it.longitude in -180.0..180.0)
        }
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
            assertEquals(
                markers.map { it.id }.toSet(),
                clustered.flatMap { c -> c.markers.map { it.id } }.toSet(),
            )
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
    }

    private fun marker(id: String, latitude: Double, longitude: Double) =
        MapMarker(id = id, title = id, latitude = latitude, longitude = longitude, isMemory = true)
}
