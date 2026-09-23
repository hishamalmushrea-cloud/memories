package com.memorymap.domain.repository

import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.DayContentCounts
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.OnThisDayItem
import com.memorymap.domain.model.Person
import com.memorymap.domain.model.Place
import com.memorymap.domain.model.User
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow

/** Memories: create, read, update, soft-delete. */
interface MemoryRepository {
    fun watchAll(userId: String): Flow<List<Memory>>
    fun watchLocated(userId: String): Flow<List<Memory>>
    fun watchByDate(userId: String, date: LocalDate): Flow<List<Memory>>
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
    fun watchDayCounts(userId: String, from: LocalDate, to: LocalDate): Flow<List<DayContentCounts>>
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
    suspend fun clearLocal()
}
