package com.memorymap.testing

import com.memorymap.domain.model.AuthState
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Memory
import com.memorymap.domain.model.Person
import com.memorymap.domain.model.Place
import com.memorymap.domain.model.User
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.ReferenceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal auth double: a fixed account id and no network.
 *
 * ViewModel tests need "who is signed in" and nothing else, so this replaces the
 * Supabase-backed repository without touching a socket.
 */
class FakeAuthRepository(userId: String?) : AuthRepository {

    private val id = MutableStateFlow(userId)

    override val currentUserId: StateFlow<String?> = id.asStateFlow()

    override val authState: StateFlow<AuthState> = MutableStateFlow(
        if (userId == null) {
            AuthState.SignedOut
        } else {
            AuthState.SignedIn(User(id = userId, email = "", displayName = ""), false)
        },
    )

    override val isCloudConfigured: Boolean = false

    override suspend fun restoreSession() = Unit

    override suspend fun signUp(email: String, password: String, displayName: String) =
        AuthRepository.Result.Success

    override suspend fun signIn(email: String, password: String) = AuthRepository.Result.Success

    override suspend fun resetPassword(email: String) = AuthRepository.Result.Success

    override suspend fun signOut() {
        id.value = null
    }

    override suspend fun continueOffline(displayName: String?): User =
        User(id = id.value ?: "local-user", email = "", displayName = displayName.orEmpty())
}

/**
 * Minimal people-and-places double.
 *
 * The editors only need a list to offer and somewhere for a created name to
 * land, so this keeps both in memory and never touches a database.
 */
class FakeReferenceRepository(
    people: List<Person> = emptyList(),
    places: List<Place> = emptyList(),
) : ReferenceRepository {

    private val people = people.toMutableList()
    private val places = places.toMutableList()

    /** Every name handed back by [findOrCreatePerson], in the order they were made. */
    val createdPeople: List<Person> get() = people

    override fun watchPeople(userId: String): Flow<List<Person>> = flowOf(people.toList())

    override fun watchPlaces(userId: String): Flow<List<Place>> = flowOf(places.toList())

    override suspend fun findOrCreatePerson(userId: String, name: String): Person =
        people.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: Person(userId = userId, name = name).also { people.add(it) }

    override suspend fun savePlace(place: Place) {
        places.add(place)
    }

    override suspend fun deletePerson(id: String) {
        people.removeAll { it.id == id }
    }

    override suspend fun deletePlace(id: String) {
        places.removeAll { it.id == id }
    }

    override suspend fun entriesWithPerson(personId: String): List<DailyEntry> = emptyList()

    override suspend fun memoriesWithPerson(personId: String): List<Memory> = emptyList()

    override suspend fun entriesAtPlace(placeId: String): List<DailyEntry> = emptyList()

    override suspend fun memoriesAtPlace(placeId: String): List<Memory> = emptyList()
}
