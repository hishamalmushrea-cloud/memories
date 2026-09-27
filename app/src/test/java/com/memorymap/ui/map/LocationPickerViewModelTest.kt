package com.memorymap.ui.map

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.memorymap.domain.map.MapProvider
import com.memorymap.domain.model.GeoPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Picking a place by hand.
 *
 * The design decision this file protects: the pin **is** the centre of the map. There is
 * no second way to place a pin, so "where is the point" is always "where is the map", and
 * the two cannot disagree. The other half is the zoom: a picker opened on a place should
 * start close to it, and one opened with no place should start wide enough to find one.
 *
 * The provider is a fake whose zoom bounds (3 to 12) are nowhere near a real provider's,
 * so an assertion against them proves the view model reads the provider rather than a
 * constant of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationPickerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val provider = PickerFakeProvider()

    private fun viewModel(vararg arguments: Pair<String, Any?>) =
        LocationPickerViewModel(SavedStateHandle(mapOf(*arguments)), provider)

    @Test
    fun `a point in the arguments becomes the centre, zoomed in on it`() = runTest {
        val model = viewModel("lat" to "15.3547", "lon" to "44.2066")

        assertEquals(GeoPoint(15.3547, 44.2066), model.picked)

        model.state.test {
            val state = awaitItem()
            assertEquals(GeoPoint(15.3547, 44.2066), state.center)
            // Opened on a place, the map starts where you can see the street.
            assertEquals(15.0, state.zoom, 0.0001)
        }
    }

    @Test
    fun `no point in the arguments starts wide, with nowhere claimed`() = runTest {
        val model = viewModel()

        assertEquals(MapViewModel.DEFAULT_CENTER, model.picked)

        model.state.test {
            val state = awaitItem()
            assertEquals(MapViewModel.DEFAULT_CENTER, state.center)
            // Zoomed out to find a place rather than pretending to be somewhere.
            assertEquals(3.0, state.zoom, 0.0001)
        }
    }

    @Test
    fun `a point that cannot be read is treated as no point`() = runTest {
        val model = viewModel("lat" to "north", "lon" to "44.2066")

        assertEquals(MapViewModel.DEFAULT_CENTER, model.picked)
        model.state.test {
            assertEquals(3.0, awaitItem().zoom, 0.0001)
        }
    }

    @Test
    fun `half a point is treated as no point`() = runTest {
        val model = viewModel("lat" to "15.3547")

        assertEquals(MapViewModel.DEFAULT_CENTER, model.picked)
    }

    @Test
    fun `moving the map moves the pin, because they are the same thing`() = runTest {
        val model = viewModel("lat" to "15.3547", "lon" to "44.2066")
        val scheduler = dispatcher.scheduler

        model.onCenterChange(GeoPoint(48.8566, 2.3522))

        // The pin is read straight off the centre, so no collection is needed to see it.
        assertEquals(GeoPoint(48.8566, 2.3522), model.picked)
        model.state.test {
            awaitItem()
            scheduler.advanceUntilIdle()
            assertEquals(GeoPoint(48.8566, 2.3522), expectMostRecentItem().center)
        }
    }

    @Test
    fun `the zoom is clamped to what the provider serves`() = runTest {
        val model = viewModel("lat" to "15.3547", "lon" to "44.2066")
        val scheduler = dispatcher.scheduler

        model.state.test {
            awaitItem()
            model.onZoomChange(50.0)
            scheduler.advanceUntilIdle()
            // Asking a tile server for a zoom it does not have is a blank screen, so the
            // ceiling is the provider's rather than a number this screen decided.
            assertEquals(12.0, expectMostRecentItem().zoom, 0.0001)

            model.onZoomChange(-5.0)
            scheduler.advanceUntilIdle()
            assertEquals(3.0, expectMostRecentItem().zoom, 0.0001)
        }
    }

    @Test
    fun `a zoom inside the provider's range is kept as it is`() = runTest {
        val model = viewModel("lat" to "15.3547", "lon" to "44.2066")
        val scheduler = dispatcher.scheduler

        model.state.test {
            awaitItem()
            model.onZoomChange(7.5)
            scheduler.advanceUntilIdle()
            assertEquals(7.5, expectMostRecentItem().zoom, 0.0001)
        }
    }
}

/**
 * A tile server that serves zoom 3 to 12.
 *
 * The bounds are deliberately unlike any real provider's: an assertion against them proves
 * the screen reads the provider, and a hardcoded 15 or 19 in the view model would fail it.
 */
private class PickerFakeProvider : MapProvider {
    override val id: String = "fake"
    override val attribution: String = "© test tiles"
    override val minZoom: Int = 3
    override val maxZoom: Int = 12
    override fun tileUrl(zoom: Int, x: Int, y: Int): String = "https://example.invalid/$zoom/$x/$y.png"
}
