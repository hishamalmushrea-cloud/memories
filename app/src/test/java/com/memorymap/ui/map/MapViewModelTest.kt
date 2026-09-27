package com.memorymap.ui.map

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.repository.DiaryRepositoryImpl
import com.memorymap.data.repository.MemoryRepositoryImpl
import com.memorymap.domain.map.MapProvider
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.model.Memory
import com.memorymap.domain.usecase.MapCluster
import com.memorymap.testing.FakeAuthRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The map screen: what becomes a pin, where the camera starts, and who is allowed to
 * move it.
 *
 * The camera rules are the reason this class exists. The specification's map section
 * asks for markers, clustering and a manual pick, and the behaviour that is easy to get
 * wrong is politeness: the map may fly to the user's own content once, and after that it
 * belongs to the person panning it. A map that recentres on every new record is a map
 * that cannot be read.
 *
 * The zoom ceilings come from the provider rather than from a constant here, so the fake
 * provider below has small, deliberately unreal bounds: a test that asserts `maxZoom`
 * means something only if the number could have been got wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MemoryMapDatabase
    private lateinit var memories: MemoryRepositoryImpl
    private lateinit var diary: DiaryRepositoryImpl

    private val userId = "user-1"
    private val day = LocalDate.of(2026, 9, 23)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        memories = MemoryRepositoryImpl(db.memoryDao())
        diary = DiaryRepositoryImpl(db.dailyEntryDao(), db.diaryNoteDao())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    @Test
    fun `only what has a place becomes a pin, and both kinds do`() = runTest {
        memories.save(memory("في صنعاء", at = GeoPoint(15.35, 44.20)))
        memories.save(memory("بلا مكان"))
        diary.saveEntry(entry("في عدن", at = GeoPoint(12.79, 45.03)))

        val viewModel = viewModel()
        viewModel.state.test {
            // Waiting for the camera matters: the grid is measured in pixels at the
            // current zoom, so the cluster count is only a fact once the zoom is.
            val loaded = awaitWhere { it.markerCount == 2 && it.zoom == MapViewModel.CONTENT_ZOOM }
            assertEquals(2, loaded.markerCount)
            // One pin per record: the cell is 0.044 degrees at this zoom and they are
            // two and a half degrees apart, so no arrangement of the grid can merge them.
            assertEquals(2, loaded.clusters.size)
            val titles = loaded.clusters.flatMap { it.markers }.map { it.title }.sorted()
            assertEquals(listOf("في صنعاء", "في عدن"), titles)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The one-time part is the point: a second record must not move the camera.
     */
    @Test
    fun `the camera flies to the content once and then leaves the person alone`() = runTest {
        memories.save(memory("الأولى", at = GeoPoint(15.35, 44.20)))

        val viewModel = viewModel()
        viewModel.state.test {
            val flown = awaitWhere {
                it.markerCount == 1 && it.zoom == MapViewModel.CONTENT_ZOOM
            }
            assertEquals(GeoPoint(15.35, 44.20), flown.center)

            // The person pans somewhere else entirely.
            val chosen = GeoPoint(48.85, 2.35)
            viewModel.onCenterChange(chosen)
            assertEquals(chosen, awaitWhere { it.center == chosen }.center)

            // A new record arrives, and the map stays where it was put.
            memories.save(memory("الثانية", at = GeoPoint(12.79, 45.03)))
            val after = awaitWhere { it.markerCount == 2 }
            assertEquals(chosen, after.center)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `zoom is kept inside what the provider can actually draw`() = runTest {
        val viewModel = viewModel()
        val provider = provider

        viewModel.state.test {
            awaitItem()

            viewModel.onZoomChange(99.0)
            assertEquals(
                provider.maxZoom.toDouble(),
                awaitWhere { it.zoom == provider.maxZoom.toDouble() }.zoom,
                0.001,
            )

            viewModel.onZoomChange(-4.0)
            assertEquals(
                provider.minZoom.toDouble(),
                awaitWhere { it.zoom == provider.minZoom.toDouble() }.zoom,
                0.001,
            )

            // Both of those pass through the clamp on the way to a value the fake
            // provider allows: 12 at the top, 3 at the bottom. Asserting the awaited
            // state is the clamp's own output is what makes the test about the clamp
            // rather than about arithmetic that happens to work out.
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `opening a cluster centres on it and zooms in by two`() = runTest {
        val cluster = MapCluster(
            latitude = 15.35,
            longitude = 44.20,
            markers = emptyList(),
        )
        val viewModel = viewModel()

        viewModel.state.test {
            val before = awaitItem()
            viewModel.zoomTo(cluster)

            val after = awaitWhere { it.center == GeoPoint(15.35, 44.20) }
            assertEquals(before.zoom + 2, after.zoom, 0.001)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a cluster at the provider ceiling stops there`() = runTest {
        val viewModel = viewModel()
        val provider = provider

        viewModel.state.test {
            awaitItem()
            viewModel.onZoomChange(provider.maxZoom.toDouble())
            awaitWhere { it.zoom == provider.maxZoom.toDouble() }

            viewModel.zoomTo(MapCluster(15.35, 44.20, emptyList()))

            val after = awaitWhere { it.center == GeoPoint(15.35, 44.20) }
            assertEquals(provider.maxZoom.toDouble(), after.zoom, 0.001)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Asking for the position is the only thing that may bring the camera to it, and it
     * must not undo a zoom the person chose.
     */
    @Test
    fun `the position brings the camera to it without zooming out`() = runTest {
        val viewModel = viewModel()
        val here = GeoPoint(15.35, 44.20)

        viewModel.state.test {
            awaitItem()
            viewModel.onUserLocation(here)

            val arrived = awaitWhere { it.userLocation != null }
            assertEquals(here, arrived.center)
            assertEquals(MapViewModel.CONTENT_ZOOM, arrived.zoom, 0.001)

            // Closer in than the content zoom, and inside what this provider draws:
            // asking again must not pull the map back out.
            val closer = provider.maxZoom.toDouble()
            viewModel.onZoomChange(closer)
            awaitWhere { it.zoom == closer }
            viewModel.onUserLocation(GeoPoint(15.40, 44.25))

            val again = awaitWhere { it.userLocation == GeoPoint(15.40, 44.25) }
            assertEquals(closer, again.zoom, 0.001)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `forgetting the position stops showing it`() = runTest {
        val viewModel = viewModel()

        viewModel.state.test {
            awaitItem()
            viewModel.onUserLocation(GeoPoint(15.35, 44.20))
            awaitWhere { it.userLocation != null }

            viewModel.clearUserLocation()

            assertNull(awaitWhere { it.userLocation == null }.userLocation)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * Two records at one place are one pin, and they stay one pin however far in the
     * map goes - which is the right answer, because there is nothing to separate.
     */
    @Test
    fun `two records at the same place are one pin`() = runTest {
        val here = GeoPoint(15.3500, 44.2000)
        memories.save(memory("هنا", at = here))
        memories.save(memory("هنا أيضًا", at = here))

        val viewModel = viewModel()
        viewModel.state.test {
            val loaded = awaitWhere { it.markerCount == 2 && it.zoom == MapViewModel.CONTENT_ZOOM }
            assertEquals(1, loaded.clusters.size)
            assertEquals(2, loaded.clusters.single().size)

            viewModel.onZoomChange(provider.maxZoom.toDouble())

            val closer = awaitWhere { it.zoom == provider.maxZoom.toDouble() }
            assertEquals("nothing to separate, so nothing separates", 1, closer.clusters.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The other half of the rule: two places 0.05 degrees apart are one pin when the map
     * is wide and two when it is close.
     *
     * Only the close half is asserted. At the provider's highest zoom the grid cell is
     * 0.022 degrees, so a 0.05-degree gap cannot be bridged by any placement of the
     * grid - whereas at a wide zoom the same two points may fall either side of a cell
     * boundary, and an assertion there would be a coin toss that passes until it does
     * not. The merging side is `MapClusteringTest`'s job, with numbers it can prove.
     */
    @Test
    fun `zooming in splits two places that a wider map would merge`() = runTest {
        memories.save(memory("هنا", at = GeoPoint(15.3500, 44.2000)))
        memories.save(memory("قريب", at = GeoPoint(15.4000, 44.2000)))

        val viewModel = viewModel()
        viewModel.state.test {
            awaitWhere { it.markerCount == 2 && it.zoom == MapViewModel.CONTENT_ZOOM }

            viewModel.onZoomChange(provider.maxZoom.toDouble())

            val close = awaitWhere { it.zoom == provider.maxZoom.toDouble() }
            assertEquals(2, close.clusters.size)
            assertTrue(close.clusters.all { it.isSingle })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `with no account there is nothing to draw`() = runTest {
        memories.save(memory("لمن لا حساب", at = GeoPoint(15.35, 44.20)))

        val viewModel = viewModel(auth = FakeAuthRepository(userId = null))
        viewModel.state.test {
            val state = awaitWhere { !it.isLoading }
            assertEquals(0, state.markerCount)
            assertTrue(state.clusters.isEmpty())
            assertEquals(MapViewModel.DEFAULT_CENTER, state.center)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private val provider = FakeMapProvider()

    private fun viewModel(auth: FakeAuthRepository = FakeAuthRepository(userId)) = MapViewModel(
        memoryRepository = memories,
        diaryRepository = diary,
        authRepository = auth,
        provider = provider,
    )

    private fun memory(title: String, at: GeoPoint? = null) = Memory(
        userId = userId,
        title = title,
        location = at,
        memoryDate = day,
    )

    private fun entry(title: String, at: GeoPoint? = null) = DailyEntry(
        userId = userId,
        date = day,
        time = LocalDateTime.of(day, LocalTime.of(9, 30)),
        title = title,
        emotion = Emotion.HAPPY,
        location = at,
    )

    /** Skips intermediate emissions until one satisfies [predicate]. */
    private suspend fun ReceiveTurbine<MapUiState>.awaitWhere(
        predicate: (MapUiState) -> Boolean,
    ): MapUiState {
        var state = awaitItem()
        while (!predicate(state)) {
            state = awaitItem()
        }
        return state
    }
}

/**
 * A tile server that draws between zoom 3 and zoom 12.
 *
 * The bounds are deliberately nowhere near a real provider's: an assertion against
 * `maxZoom` proves the view model reads the provider rather than a constant of its own
 * only if the number could not have been guessed.
 */
private class FakeMapProvider : MapProvider {
    override val id: String = "fake"
    override val attribution: String = "© test tiles"
    override val minZoom: Int = 3
    override val maxZoom: Int = 12
    override fun tileUrl(zoom: Int, x: Int, y: Int): String = "https://example.invalid/$zoom/$x/$y.png"
}
