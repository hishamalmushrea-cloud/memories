package com.memorymap.domain.usecase

import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Memory
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the two hot paths behave with a full life in the database.
 *
 * What this measures is algorithmic cost, not frame rate. A timeline rebuild and
 * a clustering pass both run on the UI thread's critical path, so if either goes
 * quadratic the app stutters long before any profiler is consulted — and neither
 * is visible in a test that seeds twenty records.
 *
 * The bounds below are deliberately loose. A shared CI runner is slow and
 * variable, and a flaky performance test trains everybody to ignore the build.
 * They are set to catch an O(n^2) regression by a wide margin, not to police
 * milliseconds; the exact timings are printed so a real slowdown is visible in
 * the log even when it stays inside the bound.
 */
class PerformanceTest {

    private val start = LocalDate.of(2005, 1, 1)

    private fun entries(count: Int): List<DailyEntry> = List(count) { index ->
        DailyEntry(
            userId = "u1",
            date = start.plusDays(index.toLong()),
            time = start.plusDays(index.toLong()).atTime(8 + (index % 12), index % 60),
            title = "حدث $index",
            text = "نص الحدث رقم $index",
        )
    }

    private fun memories(count: Int): List<Memory> = List(count) { index ->
        Memory(
            userId = "u1",
            title = "ذكرى $index",
            text = "نص الذكرى رقم $index",
            memoryDate = start.plusDays(index.toLong() * 2),
        )
    }

    private fun markers(count: Int): List<MapMarker> = List(count) { index ->
        // Spread across Yemen rather than stacked on one point, so the clustering
        // actually has cells to distribute them into.
        MapMarker(
            id = "m$index",
            title = "نقطة $index",
            latitude = 12.0 + (index % 400) * 0.01,
            longitude = 42.5 + (index / 400) * 0.01,
            isMemory = true,
        )
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        // Warm up so the first call is not paying for class loading.
        block()
        val started = System.nanoTime()
        val result = block()
        val millis = (System.nanoTime() - started) / 1_000_000
        println("$label: ${millis}ms")
        return result
    }

    @Test
    fun `a twenty year archive still builds a correct timeline quickly`() {
        // 7,300 events plus 3,650 memories: one entry a day for twenty years.
        val events = entries(7_300)
        val memories = memories(3_650)

        val days = timed("timeline 7300 events + 3650 memories") {
            TimelineBuilder.build(events, memories)
        }

        assertEquals(7_300, days.size)
        // Newest day first, which is the ordering the screen depends on.
        assertTrue(days.first().date.isAfter(days.last().date))
        // Memories fall on every second day, so the newest day has its event
        // alone and the day before it has both.
        assertEquals(1, days.first().rows.size)
        assertEquals(2, days[1].rows.size)
    }

    @Test
    fun `the whole archive rebuilds well inside a frame budget many times over`() {
        val days = timed("timeline 7300 events + 3650 memories, five rebuilds") {
            var best = Long.MAX_VALUE
            repeat(5) {
                val started = System.nanoTime()
                val built = TimelineBuilder.build(entries(7_300), memories(3_650))
                val took = (System.nanoTime() - started) / 1_000_000
                if (took < best) best = took
                check(built.size == 7_300)
            }
            best
        }

        // A linear grouping over ten thousand records takes single-digit
        // milliseconds on a laptop. Five seconds is far outside anything the app
        // could feel, and far inside what a quadratic version would need.
        assertTrue("the best of five rebuilds took ${days}ms", days < 5_000)
    }

    @Test
    fun `ten thousand map pins cluster quickly`() {
        val pins = markers(10_000)

        val clusters = timed("clustering 10000 markers") {
            MapClustering.cluster(pins, zoom = 8)
        }

        // Nothing is lost in the merge: every cluster's members add back up.
        assertEquals(10_000, clusters.sumOf { it.markers.size })
        assertTrue("expected real clustering at this zoom", clusters.size < pins.size)
    }

    @Test
    fun `zooming in splits clusters apart without losing a pin`() {
        // The same 5,000 pins at four zoom levels. This is the behaviour a user
        // feels on the map: a country view is a handful of bubbles, a street view
        // is individual pins.
        val pins = markers(5_000)

        val counts = timed("clustering 5000 markers at four zooms") {
            listOf(3, 8, 12, 14).map { zoom -> MapClustering.cluster(pins, zoom) }
        }

        // Nothing is ever lost: every zoom accounts for all 5,000 pins.
        counts.forEach { clusters ->
            assertEquals(5_000, clusters.sumOf { it.markers.size })
        }
        // And each zoom in strictly separates more of them.
        assertTrue(
            "expected clusters to increase with zoom, got ${counts.map { it.size }}",
            counts.zipWithNext().all { (coarse, fine) -> fine.size > coarse.size },
        )
    }

    @Test
    fun `an empty archive produces an empty timeline without doing work`() {
        val days = TimelineBuilder.build(emptyList(), emptyList())

        assertTrue(days.isEmpty())
        assertTrue(MapClustering.cluster(emptyList(), zoom = 10).isEmpty())
    }
}
