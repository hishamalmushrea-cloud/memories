package com.memorymap.ui.search

import androidx.lifecycle.SavedStateHandle
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.SearchResults
import com.memorymap.domain.usecase.SearchQuery
import com.memorymap.testing.FakeAuthRepository
import com.memorymap.testing.RecordingSearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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

/**
 * The search box.
 *
 * Two decisions are worth testing, and both are about what reaches the database. First:
 * the query is **parsed** before it is searched, so `2026` is a year filter rather than a
 * text scan for the digits - the specification rules out a model interpreting the query,
 * so the meaning of what was typed has to come from patterns that can be pointed at.
 * Second: typing schedules a search rather than running one per keystroke, and a pending
 * run is cancelled when a newer keystroke arrives - so a result for a half-typed word can
 * never land after the result for the finished one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    /** Long enough to cover the debounce in the view model, with room to spare. */
    private val pastDebounce = 300L

    /**
     * Shorter than the debounce, so the pending run is still waiting when the next
     * keystroke arrives. The first version of the cancellation test used the long value
     * and passed its name while proving the opposite: advancing past the debounce ran the
     * first search, so the repository saw both words and the assertion was about the
     * wrong thing.
     */
    private val beforeDebounce = 100L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        repository: RecordingSearchRepository = RecordingSearchRepository(),
        userId: String? = "user-1",
        query: String? = null,
    ) = SearchViewModel(
        searchRepository = repository,
        authRepository = FakeAuthRepository(userId),
        savedStateHandle = SavedStateHandle(query?.let { mapOf("query" to it) } ?: emptyMap()),
    )

    @Test
    fun `a query that arrived with the screen is searched without typing`() = runTest {
        val repository = RecordingSearchRepository()
        val model = viewModel(repository, query = "قهوة")

        advanceUntilIdle()

        // Arriving from a person or a place, the search is already what the person meant
        // to do; asking them to press enter again would be a step for nothing.
        assertEquals(1, repository.queries.size)
        assertEquals("قهوة", repository.queries.single().raw)
        assertTrue(model.state.value.hasSearched)
    }

    @Test
    fun `nothing is searched while the query is still being typed`() = runTest {
        val repository = RecordingSearchRepository()
        val model = viewModel(repository)

        model.onTextChanged("قهوة")
        model.onTextChanged("قهوة عربية")

        // Before the debounce elapses, the screen is waiting rather than searching: each
        // keystroke would otherwise be several LIKE scans across the archive.
        assertEquals(0, repository.queries.size)
        assertFalse(model.state.value.hasSearched)

        advanceUntilIdle()

        assertEquals(1, repository.queries.size)
        assertEquals("قهوة عربية", repository.queries.single().raw)
    }

    @Test
    fun `the query is parsed, not searched as it was typed`() = runTest {
        val repository = RecordingSearchRepository()
        val model = viewModel(repository)

        model.onTextChanged("2026")
        advanceUntilIdle()

        val parsed = repository.queries.single()
        // A bare four-digit year is a year filter, which is the whole point of parsing:
        // the row that matched can be pointed at.
        assertEquals(2026, parsed.year)
        assertEquals("2026", parsed.raw)
    }

    @Test
    fun `clearing the box clears the results and stops the search`() = runTest {
        val repository = RecordingSearchRepository()
        val model = viewModel(repository)

        model.onTextChanged("قهوة")
        advanceUntilIdle()
        assertTrue(model.state.value.results != null)

        model.onTextChanged("")
        advanceUntilIdle()

        val state = model.state.value
        assertNull(state.results)
        // Back to the screen's opening state: no results and no claim that a search ran.
        assertFalse(state.hasSearched)
        assertFalse(state.isSearching)
        assertEquals(1, repository.queries.size)
    }

    @Test
    fun `a search in flight is replaced by the newer one`() = runTest {
        val repository = RecordingSearchRepository()
        val model = viewModel(repository)

        model.onTextChanged("قه")
        advanceTimeBy(beforeDebounce)
        model.onTextChanged("قهوة")
        advanceUntilIdle()

        // Only the finished word was searched: the earlier keystroke's run was cancelled
        // before it reached the database, which is what keeps results in order.
        assertEquals(listOf("قهوة"), repository.queries.map { it.raw })
    }

    @Test
    fun `with no signed-in account the result is empty rather than an error`() = runTest {
        val repository = RecordingSearchRepository()
        val model = viewModel(repository, userId = null)

        model.onTextChanged("قهوة")
        advanceUntilIdle()

        assertTrue(repository.queries.isEmpty())
        val results = model.state.value.results
        assertEquals("قهوة", results?.query?.raw)
        assertTrue(results?.isEmpty == true)
        assertTrue(model.state.value.hasSearched)
    }

    @Test
    fun `a failing search shows an empty result instead of crashing`() = runTest {
        val repository = RecordingSearchRepository()
        repository.fails = true
        val model = viewModel(repository)

        model.onTextChanged("قهوة")
        advanceUntilIdle()

        val state = model.state.value
        assertTrue(state.results?.isEmpty == true)
        assertFalse(state.isSearching)
        // The screen still counts as searched: "nothing matched" is the honest answer for
        // a query that failed, and the failure is in the log.
        assertTrue(state.hasSearched)
    }

    @Test
    fun `a filter with an empty box does not list the whole archive`() = runTest {
        val repository = RecordingSearchRepository()
        val model = viewModel(repository)

        model.setEmotion(Emotion.HAPPY)
        model.clearFilter()
        advanceUntilIdle()

        // A filter on its own would be a search for everything, which is not a search.
        assertTrue(repository.queries.isEmpty())
        assertNull(model.state.value.results)
    }

    @Test
    fun `a filter re-runs the search that is on screen`() = runTest {
        val repository = RecordingSearchRepository()
        val model = viewModel(repository)

        model.onTextChanged("قهوة")
        advanceUntilIdle()
        model.setEmotion(Emotion.HAPPY)
        advanceUntilIdle()

        assertEquals(2, repository.queries.size)
        assertEquals(Emotion.HAPPY, repository.filters.last().emotion)
        assertEquals("قهوة", repository.queries.last().raw)
    }

    @Test
    fun `clearing the filter searches again, unconstrained`() = runTest {
        val repository = RecordingSearchRepository()
        val model = viewModel(repository)

        model.onTextChanged("قهوة")
        advanceUntilIdle()
        model.setEmotion(Emotion.SAD)
        advanceUntilIdle()
        model.clearFilter()
        advanceUntilIdle()

        assertEquals(3, repository.queries.size)
        assertTrue(repository.filters.last().isUnconstrained)
    }

    @Test
    fun `the results that come back are the ones on screen`() = runTest {
        val repository = RecordingSearchRepository(
            results = { query: SearchQuery ->
                SearchResults(query = query, memories = emptyList(), entries = emptyList())
            },
        )
        val model = viewModel(repository)

        model.onTextChanged("قهوة")
        advanceUntilIdle()

        val state = model.state.value
        assertFalse(state.isSearching)
        assertEquals("قهوة", state.results?.query?.raw)
    }
}
