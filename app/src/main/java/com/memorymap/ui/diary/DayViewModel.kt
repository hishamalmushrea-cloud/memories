package com.memorymap.ui.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.OnThisDayItem
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.domain.repository.OnThisDayRepository
import com.memorymap.domain.repository.UserRepository
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state of one diary day. */
data class DayUiState(
    val date: LocalDate = LocalDate.now(),
    val entries: List<DailyEntry> = emptyList(),
    val diaryNote: String = "",
    val onThisDay: List<OnThisDayItem> = emptyList(),
    val isLoading: Boolean = true,
)

/**
 * Backs the day screen. The day is the basic unit of the diary, so this is the
 * first screen with a real data path: Room -> repository -> StateFlow -> Compose.
 *
 * Everything is read from the local database, so the screen works with no
 * network at all.
 */
@HiltViewModel
class DayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val diaryRepository: DiaryRepository,
    private val onThisDayRepository: OnThisDayRepository,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val date: LocalDate = savedStateHandle.get<String>("date")
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now()

    private val _state = MutableStateFlow(DayUiState(date = date))
    val state: StateFlow<DayUiState> = _state.asStateFlow()

    /**
     * Phase 1 has no authentication yet, so records are read under the local
     * placeholder owner. Phase 2 replaces this with the signed-in user id.
     */
    private var userId: String = LOCAL_USER_ID

    init {
        observe()
    }

    private fun observe() {
        viewModelScope.launch {
            userRepository.watchCurrentUser().collect { user ->
                if (user != null) userId = user.id
            }
        }

        viewModelScope.launch {
            diaryRepository.watchDay(userId, date).collect { entries ->
                _state.update { it.copy(entries = entries, isLoading = false) }
            }
        }

        viewModelScope.launch {
            // The day note is a single row, refreshed when the day is opened.
            _state.update { it.copy(diaryNote = diaryRepository.getDiaryNote(userId, date).orEmpty()) }
            _state.update { it.copy(onThisDay = onThisDayRepository.items(userId, date)) }
        }
    }

    /** Saves the end-of-day note. Written by the user, never generated. */
    fun saveDiaryNote(text: String) {
        viewModelScope.launch {
            runCatching { diaryRepository.saveDiaryNote(userId, date, text) }
                .onFailure { MmLog.e("Unable to save the diary note", it) }
            _state.update { it.copy(diaryNote = text) }
        }
    }

    /** Deletes one event of the day, keeping the tombstone for the next sync. */
    fun deleteEntry(entryId: String) {
        viewModelScope.launch {
            runCatching { diaryRepository.deleteEntry(entryId) }
                .onFailure { MmLog.e("Unable to delete the entry", it) }
        }
    }

    companion object {
        const val LOCAL_USER_ID = "local"
    }
}
