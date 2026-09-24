package com.memorymap.domain.repository

import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.DayContentCounts
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.OnThisDayItem
import com.memorymap.domain.model.Person
import com.memorymap.domain.model.Place
import com.memorymap.domain.model.User
import com.memorymap.domain.model.WipeSummary
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Memories: create, read, update, soft-delete. */
interface MemoryRepository {
    fun watchAll(userId: String): Flow<List<Memory>>
    /** One memory as a stream, so the detail screen reflects edits and deletes. */
    fun watchOne(id: String): Flow<Memory?>
    fun watchLocated(userId: String): Flow<List<Memory>>
    fun watchByDate(userId: String, date: LocalDate): Flow<List<Memory>>
    /** Inclusive date range, so the timeline can read a bounded window. */
    fun watchBetween(userId: String, from: LocalDate, to: LocalDate): Flow<List<Memory>>
    suspend fun getById(id: String): Memory?
    suspend fun save(memory: Memory, personIds: List<String> = emptyList(), placeIds: List<String> = emptyList())
    suspend fun delete(id: String)
    suspend fun search(userId: String, query: String): List<Memory>
    suspend fun count(userId: String): Int
}

/** The diary: the day is the unit, the event is what happens inside it. */
interface DiaryRepository {
    fun watchDay(userId: String, date: LocalDate): Flow<List<DailyEntry>>
    fun watchRange(userId: String, from: LocalDate, to: LocalDate): Flow<List<DailyEntry>>
    /** Events that carry a location, which is what the map can show. */
    fun watchLocated(userId: String): Flow<List<DailyEntry>>
    fun watchDayCounts(userId: String, from: LocalDate, to: LocalDate): Flow<List<DayContentCounts>>
    /** One event, used by the editor to load what it is editing. */
    suspend fun getEntry(id: String): DailyEntry?
    suspend fun saveEntry(entry: DailyEntry, personIds: List<String> = emptyList(), placeIds: List<String> = emptyList())
    suspend fun deleteEntry(id: String)
    suspend fun getDiaryNote(userId: String, date: LocalDate): String?
    suspend fun saveDiaryNote(userId: String, date: LocalDate, text: String)
    suspend fun search(userId: String, query: String): List<DailyEntry>
}

/** People and places, the two linkable nouns of the app. */
interface ReferenceRepository {
    fun watchPeople(userId: String): Flow<List<Person>>
    fun watchPlaces(userId: String): Flow<List<Place>>
    suspend fun findOrCreatePerson(userId: String, name: String): Person
    suspend fun savePlace(place: Place)
    suspend fun deletePerson(id: String)
    suspend fun deletePlace(id: String)
    suspend fun entriesWithPerson(personId: String): List<DailyEntry>
    suspend fun memoriesWithPerson(personId: String): List<Memory>
    suspend fun entriesAtPlace(placeId: String): List<DailyEntry>
    suspend fun memoriesAtPlace(placeId: String): List<Memory>
}

/** "On this day": same month and day in earlier years. */
interface OnThisDayRepository {
    suspend fun items(userId: String, day: LocalDate): List<OnThisDayItem>
}

/** Account and local identity. */
interface UserRepository {
    fun watchCurrentUser(): Flow<User?>
    suspend fun getById(id: String): User?
    suspend fun save(user: User)

    /**
     * Deletes every trace of [userId] from this device: memories, events, diary
     * notes, people, places, the attachment rows, the media files themselves and
     * the sync bookmark.
     *
     * This is irreversible and cannot be undone from the app, which is why the
     * caller has to confirm it explicitly. It touches this device only; cloud
     * data is a separate step the user takes on the server.
     */
    suspend fun deleteLocalData(userId: String): WipeSummary
}
