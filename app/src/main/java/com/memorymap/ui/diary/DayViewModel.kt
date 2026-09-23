package com.memorymap.ui.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.OnThisDayItem
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.domain.repository.OnThisDayRepository
import com.memorymap.util.MmLog
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
 * screen with the real data path: Room -> repository -> StateFlow -> Compose.
 *
 * Every read is local, and it is scoped to whoever is signed in, which is why
 * the stream restarts when the account changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val diaryRepository: DiaryRepository,
    private val onThisDayRepository: OnThisDayRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val date: LocalDate = savedStateHandle.get<String>("date")
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now()

    private val note = MutableStateFlow<String?>(null)
    private val memories = MutableStateFlow<List<OnThisDayItem>>(emptyList())

    val state: StateFlow<DayUiState> = authRepository.currentUserId
        .flatMapLatest { userId ->
            if (userId == null) {
                flowOf(DayUiState(date = date, isLoading = true))
            } else {
                combine(
                    diaryRepository.watchDay(userId, date),
                    note,
                    memories,
                ) { entries, diaryNote, onThisDay ->
                    DayUiState(
                        date = date,
                        entries = entries,
                        diaryNote = diaryNote.orEmpty(),
                        onThisDay = onThisDay,
                        isLoading = false,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayUiState(date = date))

    init {
        // The note and the "on this day" list are single reads, refreshed
        // whenever the account or the day changes.
        viewModelScope.launch {
            authRepository.currentUserId.collect { userId ->
                if (userId == null) return@collect
                runCatching {
                    note.value = diaryRepository.getDiaryNote(userId, date)
                    memories.value = onThisDayRepository.items(userId, date)
                }.onFailure { MmLog.e("Unable to load the day", it) }
            }
        }
    }

    /** Saves the end-of-day note. Written by the user, never generated. */
    fun saveDiaryNote(text: String) {
        viewModelScope.launch {
            note.value = text
            runCatching { currentUserId()?.let { diaryRepository.saveDiaryNote(it, date, text) } }
                .onFailure { MmLog.e("Unable to save the diary note", it) }
        }
    }

    /** Deletes one event of the day, keeping the tombstone for the next sync. */
    fun deleteEntry(entryId: String) {
        viewModelScope.launch {
            runCatching { diaryRepository.deleteEntry(entryId) }
                .onFailure { MmLog.e("Unable to delete the entry", it) }
        }
    }

    /** The account every write is scoped to; null before a session exists. */
    private fun currentUserId(): String? = authRepository.currentUserId.value
}
