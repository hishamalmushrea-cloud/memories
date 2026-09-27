package com.memorymap.ui.search

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.memorymap.R
import com.memorymap.domain.model.GeoPoint
import com.memorymap.domain.model.Person
import com.memorymap.domain.repository.ReferenceRepository
import com.memorymap.navigation.Routes
import com.memorymap.testing.FailableReferenceRepository
import com.memorymap.testing.FakeAuthRepository
import com.memorymap.testing.FakeReferenceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The names an archive is organised around.
 *
 * Adding a name is a deliberate action. If it cannot be stored, the field must
 * not empty itself and leave the person wondering whether the tap registered -
 * that is the difference between a screen that failed and a screen that ignores
 * you. Each test uses a repository that is real in every way except the one call
 * under test, written as a delegation over the interface so it cannot drift.
 *
 * The state is a `stateIn` of a combine, so it only runs while somebody is
 * collecting: each test opens a Turbine block to hold that subscription, drives the
 * view model through it, and then takes the latest value with
 * `expectMostRecentItem()`. Reading `state.value` instead would leave the emissions
 * between here and there unconsumed, and Turbine fails a block that ends with events
 * it was never shown - which is exactly how the first version of this file failed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrganizationViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val handle = SavedStateHandle()

    private fun viewModel(repository: ReferenceRepository, userId: String? = "user-1") =
        OrganizationViewModel(
            referenceRepository = repository,
            authRepository = FakeAuthRepository(userId),
            savedStateHandle = handle,
        )

    @Test
    fun `a name that cannot be added says so and keeps the text`() = runTest {
        val viewModel = viewModel(FailableReferenceRepository())
        val scheduler = dispatcher.scheduler
        viewModel.state.test {
            awaitItem()
            viewModel.onDraftNameChanged("أحمد")
            viewModel.addPerson()
            scheduler.advanceUntilIdle()

            val state = expectMostRecentItem()
            assertEquals(R.string.error_name_not_added, state.errorRes)
            // The text is still there, so the tap can simply be repeated.
            assertEquals("أحمد", state.draftName)
        }
    }

    @Test
    fun `a place that cannot be added says so and keeps both the name and the pin`() =
        runTest {
            val viewModel = viewModel(FailableReferenceRepository())
            val scheduler = dispatcher.scheduler
            viewModel.state.test {
                awaitItem()
                // A place arrives the way the map does: through the picker result.
                handle[Routes.RESULT_LOCATION] = "15.3547,44.2066"
                scheduler.advanceUntilIdle()
                viewModel.onDraftNameChanged("بيت الجدة")
                viewModel.addPlace()
                scheduler.advanceUntilIdle()

                val state = expectMostRecentItem()
                assertEquals(R.string.error_name_not_added, state.errorRes)
                assertEquals("بيت الجدة", state.draftName)
                assertEquals(GeoPoint(15.3547, 44.2066), state.draftLocation)
            }
        }

    @Test
    fun `dismissing the message leaves the rest of the screen alone`() = runTest {
        val viewModel = viewModel(FailableReferenceRepository())
        val scheduler = dispatcher.scheduler
        viewModel.state.test {
            awaitItem()
            viewModel.onDraftNameChanged("أحمد")
            viewModel.addPerson()
            scheduler.advanceUntilIdle()
            assertEquals(R.string.error_name_not_added, expectMostRecentItem().errorRes)

            viewModel.clearError()
            scheduler.advanceUntilIdle()

            val state = expectMostRecentItem()
            assertNull(state.errorRes)
            assertEquals("أحمد", state.draftName)
        }
    }

    @Test
    fun `a delete that fails says so and the name stays on the list`() = runTest {
        val kept = Person(userId = "user-1", name = "أحمد")
        val viewModel = viewModel(
            FailableReferenceRepository(
                FakeReferenceRepository(people = listOf(kept)),
                failAdds = false,
                failDeletes = true,
            ),
        )
        val scheduler = dispatcher.scheduler
        viewModel.state.test {
            awaitItem()
            scheduler.advanceUntilIdle()
            viewModel.deletePerson(kept.id)
            scheduler.advanceUntilIdle()

            val state = expectMostRecentItem()
            assertEquals(R.string.organization_error_delete, state.errorRes)
            assertEquals(listOf("أحمد"), state.people.map { it.name })
        }
    }

    @Test
    fun `a name that was added empties the field`() = runTest {
        val repository = FailableReferenceRepository(failAdds = false)
        val viewModel = viewModel(repository)
        val scheduler = dispatcher.scheduler
        viewModel.state.test {
            awaitItem()
            viewModel.onDraftNameChanged("أحمد")
            viewModel.addPerson()
            scheduler.advanceUntilIdle()

            val state = expectMostRecentItem()
            assertNull(state.errorRes)
            // The field empties only once the name is stored. The list itself is
            // not asserted here: the double snapshots the rows when the screen
            // subscribes, which is before this add, so a claim about the new row
            // would be a claim about the double rather than about the view model.
            assertEquals("", state.draftName)
        }
    }

    @Test
    fun `with no account there is nothing to add to`() = runTest {
        val repository = FailableReferenceRepository()
        val viewModel = viewModel(repository, userId = null)
        val scheduler = dispatcher.scheduler
        viewModel.state.test {
            awaitItem()
            viewModel.onDraftNameChanged("أحمد")
            viewModel.addPerson()
            scheduler.advanceUntilIdle()

            assertNull(expectMostRecentItem().errorRes)
            // Nothing was attempted, so nothing can have failed: this is a
            // signed-out screen, not a broken one.
            assertEquals(0, repository.addAttempts)
        }
    }

}
