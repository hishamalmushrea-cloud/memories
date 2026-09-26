package com.memorymap.ui.nearby

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import com.memorymap.data.local.MemoryMapDatabase
import com.memorymap.data.repository.DiaryRepositoryImpl
import com.memorymap.data.repository.MemoryRepositoryImpl
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.model.Memory
import com.memorymap.testing.FakeAuthRepository
import com.memorymap.util.Geo
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Near by": the radius is the spec's kilometre, the order is by distance, and
 * nothing is filtered at all until the user asks for a position.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NearbyViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: MemoryMapDatabase
    private lateinit var diary: DiaryRepositoryImpl
    private lateinit var memories: MemoryRepositoryImpl

    private val userId = "user-1"
    private val today = LocalDate.now()

    /** Somewhere in Sanaa, so the offsets below are real distances. */
    private val origin = GeoPoint(15.35, 44.20)

    /** Roughly 400 m north of [origin]; one degree of latitude is about 111 km. */
    private val insideOffset = 0.0036

    /** Roughly 5 km north of [origin], which is outside the radius. */
    private val outsideOffset = 0.045

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MemoryMapDatabase::class.java,
        ).allowMainThreadQueries().build()
        diary = DiaryRepositoryImpl(db.dailyEntryDao(), db.diaryNoteDao())
        memories = MemoryRepositoryImpl(db.memoryDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `nothing is filtered before a position is asked for`() = runTest {
        memories.save(memory("في الموقع", 0.0))

        viewModel(FakeAuthRepository(userId)).state.test {
            val state = awaitWhere { !it.isLoading && it.locatedCount == 1 }

            assertNull("no position has been read yet", state.origin)
            assertTrue(state.items.isEmpty())
            assertEquals(Geo.DEFAULT_NEARBY_RADIUS_M, state.radiusMeters, 0.0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `only what is inside the kilometre is listed, closest first`() = runTest {
        memories.save(memory("في الموقع", 0.0))
        diary.saveEntry(event("قريب", insideOffset))
        memories.save(memory("بعيد", outsideOffset))

        val viewModel = viewModel(FakeAuthRepository(userId))
        viewModel.onLocated(origin)

        viewModel.state.test {
            val state = awaitWhere { !it.isLoading && it.locatedCount == 3 }

            assertEquals(listOf("في الموقع", "قريب"), state.items.map { it.title })
            assertTrue("the memory is the closest", state.items[0].isMemory)
            assertFalse("the event is not a memory", state.items[1].isMemory)
            assertTrue(state.items[0].distanceMeters < state.items[1].distanceMeters)
            assertTrue(state.items[1].distanceMeters <= Geo.DEFAULT_NEARBY_RADIUS_M)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a record with no location is not counted or listed`() = runTest {
        memories.save(Memory(userId = userId, title = "بلا موقع", memoryDate = today))

        val viewModel = viewModel(FakeAuthRepository(userId))
        viewModel.onLocated(origin)

        viewModel.state.test {
            val state = awaitWhere { !it.isLoading }

            assertEquals(0, state.locatedCount)
            assertTrue(state.items.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a position that could not be read is reported and lists nothing`() = runTest {
        memories.save(memory("في الموقع", 0.0))

        val viewModel = viewModel(FakeAuthRepository(userId))
        viewModel.onLocationFailed()

        viewModel.state.test {
            val state = awaitWhere { !it.isLoading && it.locatedCount == 1 }

            assertTrue(state.locationFailed)
            assertNull(state.origin)
            assertTrue(state.items.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the position is dropped again when the screen asks`() = runTest {
        memories.save(memory("في الموقع", 0.0))

        val viewModel = viewModel(FakeAuthRepository(userId))
        viewModel.onLocated(origin)

        viewModel.state.test {
            assertEquals(1, awaitWhere { it.items.size == 1 }.items.size)

            viewModel.clearOrigin()
            val cleared = awaitWhere { it.origin == null }
            assertTrue(cleared.items.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `another account never sees this archive`() = runTest {
        memories.save(memory("خاص", 0.0))

        val viewModel = viewModel(FakeAuthRepository("someone-else"))
        viewModel.onLocated(origin)

        viewModel.state.test {
            val state = awaitWhere { !it.isLoading }

            assertEquals(0, state.locatedCount)
            assertTrue(state.items.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun viewModel(auth: FakeAuthRepository) = NearbyViewModel(
        memoryRepository = memories,
        diaryRepository = diary,
        authRepository = auth,
    )

    private fun memory(title: String, latitudeOffset: Double) = Memory(
        userId = userId,
        title = title,
        memoryDate = today,
        location = GeoPoint(origin.latitude + latitudeOffset, origin.longitude),
    )

    private fun event(title: String, latitudeOffset: Double) = DailyEntry(
        userId = userId,
        date = today,
        time = LocalDateTime.of(today, LocalTime.of(9, 0)),
        title = title,
        location = GeoPoint(origin.latitude + latitudeOffset, origin.longitude),
    )

    private suspend fun ReceiveTurbine<NearbyUiState>.awaitWhere(
        predicate: (NearbyUiState) -> Boolean,
    ): NearbyUiState {
        var state = awaitItem()
        while (!predicate(state)) {
            state = awaitItem()
        }
        return state
    }
}
