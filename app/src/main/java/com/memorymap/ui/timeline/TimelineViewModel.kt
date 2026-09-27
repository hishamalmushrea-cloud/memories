package com.memorymap.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.domain.repository.MemoryRepository
import com.memorymap.domain.usecase.TimelineBuilder
import com.memorymap.domain.usecase.TimelineDay
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

/** UI state of the life timeline. */
data class TimelineUiState(
    val days: List<TimelineDay> = emptyList(),
    val windowDays: Int = TimelineViewModel.INITIAL_WINDOW_DAYS,
    val isLoading: Boolean = true,
) {
    val rowCount: Int get() = days.sumOf { it.rows.size }
    val canLoadEarlier: Boolean get() = windowDays < TimelineViewModel.MAX_WINDOW_DAYS
}

/**
 * The life timeline: diary events and memories in one chronological stream,
 * newest day first.
 *
 * The window is bounded on purpose. Reading the whole archive at once would load
 * every record the user ever wrote, so the timeline starts with a recent window
 * and grows by one step when the user asks for earlier days.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val diaryRepository: DiaryRepository,
    private val memoryRepository: MemoryRepository,
    authRepository: AuthRepository,
) : ViewModel() {

    private val today: LocalDate = LocalDate.now()
    private val windowDays = MutableStateFlow(INITIAL_WINDOW_DAYS)

    val state: StateFlow<TimelineUiState> = authRepository.currentUserId
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(TimelineUiState())
            } else {
                // Growing the window restarts both reads, so the extra days
                // arrive from Room rather than from anything held in memory.
                windowDays.flatMapLatest { days ->
                    val from = TimelineBuilder.windowStart(today, days)
                    combine(
                        diaryRepository.watchRange(userId, from, today),
                        memoryRepository.watchBetween(userId, from, today),
                    ) { events, memories ->
                        TimelineUiState(
                            days = TimelineBuilder.build(events, memories),
                            windowDays = days,
                            isLoading = false,
                        )
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimelineUiState())

    /** Widens the window by one step, loading earlier days. */
    fun loadEarlier() {
        windowDays.value = (windowDays.value + WINDOW_STEP_DAYS).coerceAtMost(MAX_WINDOW_DAYS)
    }

    companion object {
        const val INITIAL_WINDOW_DAYS = 60
        const val WINDOW_STEP_DAYS = 60
        const val MAX_WINDOW_DAYS = 3650
    }
}
