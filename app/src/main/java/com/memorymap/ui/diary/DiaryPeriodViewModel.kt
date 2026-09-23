package com.memorymap.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.model.DayContentCounts
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.domain.usecase.DiaryTime
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Shared state for the week, month and year pages. */
data class DiaryPeriodUiState(
    val counts: List<DayContentCounts> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * Loads the per-day counters for a period and exposes them as one list, so the
 * week, month, year and calendar screens all read from the same aggregation.
 */
@HiltViewModel
class DiaryPeriodViewModel @Inject constructor(
    private val diaryRepository: DiaryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DiaryPeriodUiState())
    val state: StateFlow<DiaryPeriodUiState> = _state.asStateFlow()

    /**
     * Observes [from]..[to] inclusive. The window is deliberately bounded: an
     * unbounded timeline is what the timeline screen is for.
     */
    fun observe(from: LocalDate, to: LocalDate) {
        if (_state.value.isLoading.not() && currentFrom == from && currentTo == to) return
        currentFrom = from
        currentTo = to
        job?.cancel()
        job = viewModelScope.launch {
            runCatching {
                diaryRepository.watchDayCounts(LOCAL_USER_ID, from, to).collect { counts ->
                    _state.update { it.copy(counts = counts, isLoading = false) }
                }
            }.onFailure { MmLog.e("Unable to load the diary period", it) }
        }
    }

    /** Whole week containing [day]. */
    fun observeWeek(day: LocalDate) = observe(DiaryTime.weekStart(day), DiaryTime.weekEnd(day))

    /** Whole month containing [day]. */
    fun observeMonth(day: LocalDate) {
        val yearMonth = java.time.YearMonth.from(day)
        observe(yearMonth.atDay(1), yearMonth.atEndOfMonth())
    }

    /** Whole [year]. */
    fun observeYear(year: Int) = observe(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))

    private var currentFrom: LocalDate? = null
    private var currentTo: LocalDate? = null
    private var job: kotlinx.coroutines.Job? = null

    private companion object {
        /** Replaced by the authenticated user in Phase 2. */
        const val LOCAL_USER_ID = "local"
    }
}
