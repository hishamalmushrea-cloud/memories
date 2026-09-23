package com.memorymap.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.model.DayContentCounts
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.domain.usecase.DiaryTime
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Job
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
 *
 * The window is always bounded: an unbounded history is what the timeline is for.
 */
@HiltViewModel
class DiaryPeriodViewModel @Inject constructor(
    private val diaryRepository: DiaryRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DiaryPeriodUiState())
    val state: StateFlow<DiaryPeriodUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var currentFrom: LocalDate? = null
    private var currentTo: LocalDate? = null

    /** Observes [from]..[to] inclusive for the signed-in account. */
    fun observe(from: LocalDate, to: LocalDate) {
        if (currentFrom == from && currentTo == to && job?.isActive == true) return
        currentFrom = from
        currentTo = to
        job?.cancel()
        job = viewModelScope.launch {
            val userId = authRepository.currentUserId.value
            if (userId == null) {
                _state.update { it.copy(isLoading = true) }
                return@launch
            }
            runCatching {
                diaryRepository.watchDayCounts(userId, from, to).collect { counts ->
                    _state.update { it.copy(counts = counts, isLoading = false) }
                }
            }.onFailure { MmLog.e("Unable to load the diary period", it) }
        }
    }

    /** Whole week containing [day]. */
    fun observeWeek(day: LocalDate) = observe(DiaryTime.weekStart(day), DiaryTime.weekEnd(day))

    /** Whole month containing [day]. */
    fun observeMonth(day: LocalDate) {
        val yearMonth = YearMonth.from(day)
        observe(yearMonth.atDay(1), yearMonth.atEndOfMonth())
    }

    /** Whole [year]. */
    fun observeYear(year: Int) = observe(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31))
}
