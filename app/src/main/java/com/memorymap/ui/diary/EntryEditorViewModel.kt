package com.memorymap.ui.diary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.memorymap.R
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.DiaryRepository
import com.memorymap.util.MmLog
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI state of the diary event editor, used for both create and edit. */
data class EntryEditorUiState(
    val entryId: String,
    val isNew: Boolean = true,
    val title: String = "",
    val text: String = "",
    val date: LocalDate = LocalDate.now(),
    val hour: Int = LocalTime.now().hour,
    val minute: Int = 0,
    val emotion: Emotion? = null,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
    val errorRes: Int? = null,
) {
    val time: LocalTime get() = LocalTime.of(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
}

/**
 * Creates and edits one event inside one day.
 *
 * The day is the diary's unit and the event is what happens inside it, which is
 * what makes the timeline work: an event without a clock time could not be
 * ordered against the rest of the day.
 */
@HiltViewModel
class EntryEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val diaryRepository: DiaryRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val entryId: String = savedStateHandle.get<String>("entryId")
        ?.takeIf { it.isNotBlank() }
        ?: UUID.randomUUID().toString()

    private val initialDate: LocalDate = savedStateHandle.get<String>("date")
        ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: LocalDate.now()

    private val _state = MutableStateFlow(
        EntryEditorUiState(entryId = entryId, date = initialDate),
    )
    val state: StateFlow<EntryEditorUiState> = _state.asStateFlow()

    init {
        if (savedStateHandle.get<String>("entryId").isNullOrBlank()) return
        viewModelScope.launch {
            val existing = runCatching { diaryRepository.getEntry(entryId) }
                .onFailure { MmLog.e("Unable to load the event", it) }
                .getOrNull()
                ?: return@launch
            _state.update {
                it.copy(
                    isNew = false,
                    title = existing.title,
                    text = existing.text,
                    date = existing.date,
                    hour = existing.time.hour,
                    minute = existing.time.minute,
                    emotion = existing.emotion,
                )
            }
        }
    }

    fun onTitleChange(value: String) = _state.update { it.copy(title = value, errorRes = null) }

    fun onTextChange(value: String) = _state.update { it.copy(text = value) }

    fun onDateChange(value: LocalDate) = _state.update { it.copy(date = value) }

    fun onHourChange(value: Int) = _state.update { it.copy(hour = value.coerceIn(0, 23)) }

    fun onMinuteChange(value: Int) = _state.update { it.copy(minute = value.coerceIn(0, 59)) }

    /** Tapping the current emotion again clears it: an event need not have one. */
    fun onEmotionChange(value: Emotion) = _state.update {
        it.copy(emotion = if (it.emotion == value) null else value)
    }

    fun clearError() = _state.update { it.copy(errorRes = null) }

    fun save() {
        val current = _state.value
        if (current.isSaving || current.isSaved) return
        if (current.title.isBlank()) {
            _state.update { it.copy(errorRes = R.string.entry_error_title) }
            return
        }
        viewModelScope.launch {
            val userId = authRepository.currentUserId.value
            if (userId == null) {
                _state.update { it.copy(errorRes = R.string.memory_error_no_account) }
                return@launch
            }
            _state.update { it.copy(isSaving = true) }
            runCatching {
                diaryRepository.saveEntry(
                    DailyEntry(
                        id = entryId,
                        userId = userId,
                        date = current.date,
                        time = LocalDateTime.of(current.date, current.time),
                        title = current.title.trim(),
                        text = current.text.trim(),
                        emotion = current.emotion,
                    ),
                )
            }
                .onSuccess { _state.update { it.copy(isSaving = false, isSaved = true) } }
                .onFailure { error ->
                    MmLog.e("Unable to save the event", error)
                    _state.update { it.copy(isSaving = false, errorRes = R.string.entry_error_save) }
                }
        }
    }

    /** Soft-deletes the event; the tombstone is what the next sync replays. */
    fun delete() {
        viewModelScope.launch {
            runCatching { diaryRepository.deleteEntry(entryId) }
                .onFailure { MmLog.e("Unable to delete the event", it) }
                .onSuccess { _state.update { it.copy(isSaved = true) } }
        }
    }
}
