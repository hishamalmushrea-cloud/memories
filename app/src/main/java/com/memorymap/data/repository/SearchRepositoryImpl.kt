package com.memorymap.data.repository

import com.memorymap.data.local.Mappers.toDomain
import com.memorymap.data.local.dao.DailyEntryDao
import com.memorymap.data.local.dao.MemoryDao
import com.memorymap.data.local.dao.PersonDao
import com.memorymap.data.local.dao.PlaceDao
import com.memorymap.data.local.entities.DailyEntryEntity
import com.memorymap.data.local.entities.MemoryEntity
import com.memorymap.domain.model.DailyEntry
import com.memorymap.domain.model.Person
import com.memorymap.domain.model.Place
import com.memorymap.domain.model.SearchFilter
import com.memorymap.domain.model.SearchResults
import com.memorymap.domain.repository.SearchRepository
import com.memorymap.domain.usecase.DateConstraint
import com.memorymap.domain.usecase.SearchQuery
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs a structured search against Room.
 *
 * Text terms are AND-ed: every word the user typed has to appear, which is what
 * makes a longer query narrower rather than noisier. A person or a place named
 * in the query narrows it further, by intersecting rather than adding — asking
 * for `الأحداث مع أحمد` should not also return events that merely mention his
 * name in the text.
 */
@Singleton
class SearchRepositoryImpl @Inject constructor(
    private val memoryDao: MemoryDao,
    private val entryDao: DailyEntryDao,
    private val personDao: PersonDao,
    private val placeDao: PlaceDao,
) : SearchRepository {

    override suspend fun search(
        userId: String,
        query: SearchQuery,
        filter: SearchFilter,
    ): SearchResults {
        // Nothing to ask about: returning the whole archive would look like a
        // result and behave like a bug.
        if (userId.isBlank() || (query.isEmpty && filter.isUnconstrained)) {
            return SearchResults(query)
        }

        val dates = DateConstraint.of(query)
        val people = matchingPeople(userId, query)
        val places = matchingPlaces(userId, query)

        val memories = memoryCandidates(userId, query, people.map { it.id }, places.map { it.id })
            .map { it.toDomain() }
            .filter { dates.matches(it.memoryDate) }
            .filter { filter.emotion == null || it.emotion == filter.emotion }
            .sortedByDescending { it.memoryDate }

        val entries = entryCandidates(userId, query, people.map { it.id }, places.map { it.id })
            .map { it.toDomain() }
            .filter { dates.matches(it.date) }
            .filter { filter.emotion == null || it.emotion == filter.emotion }
            .sortedWith(compareByDescending<DailyEntry> { it.date }.thenByDescending { it.time })

        return SearchResults(
            query = query,
            memories = memories,
            entries = entries,
            people = people,
            places = places,
        )
    }

    /**
     * People whose name answers the query.
     *
     * A name written after `مع` is matched as a name; otherwise any term that
     * looks like part of a name is offered as a way in.
     */
    private suspend fun matchingPeople(userId: String, query: SearchQuery): List<Person> {
        val matched = query.person
            ?.let { personDao.search(userId, it) }
            ?: query.terms.flatMap { personDao.search(userId, it) }
        return matched.distinctBy { it.id }.map { it.toDomain() }
    }

    private suspend fun matchingPlaces(userId: String, query: SearchQuery): List<Place> {
        val matched = query.place
            ?.let { placeDao.search(userId, it) }
            ?: query.terms.flatMap { placeDao.search(userId, it) }
        return matched.distinctBy { it.id }.map { it.toDomain() }
    }

    private suspend fun memoryCandidates(
        userId: String,
        query: SearchQuery,
        personIds: List<String>,
        placeIds: List<String>,
    ): List<MemoryEntity> {
        var ids: Set<String>? = null

        if (query.terms.isNotEmpty()) {
            ids = query.terms
                .map { term -> memoryDao.search(userId, term).map { it.id }.toSet() }
                .reduce { acc, next -> acc.intersect(next) }
        }

        // Only narrowed by a person when the user actually named one; otherwise
        // merely having a person match would hide every unlinked memory.
        if (query.person != null) {
            ids = intersect(ids, personIds.flatMap { memoryDao.memoriesWithPerson(it).map { m -> m.id } }.toSet())
        }
        if (query.place != null) {
            ids = intersect(ids, placeIds.flatMap { memoryDao.memoriesAtPlace(it).map { m -> m.id } }.toSet())
        }

        return if (ids == null) memoryDao.allForUser(userId) else ids.mapNotNull { memoryDao.getById(it) }
    }

    private suspend fun entryCandidates(
        userId: String,
        query: SearchQuery,
        personIds: List<String>,
        placeIds: List<String>,
    ): List<DailyEntryEntity> {
        var ids: Set<String>? = null

        if (query.terms.isNotEmpty()) {
            ids = query.terms
                .map { term -> entryDao.search(userId, term).map { it.id }.toSet() }
                .reduce { acc, next -> acc.intersect(next) }
        }
        if (query.person != null) {
            ids = intersect(ids, personIds.flatMap { entryDao.entriesWithPerson(it).map { e -> e.id } }.toSet())
        }
        if (query.place != null) {
            ids = intersect(ids, placeIds.flatMap { entryDao.entriesAtPlace(it).map { e -> e.id } }.toSet())
        }

        return if (ids == null) entryDao.allForUser(userId) else ids.mapNotNull { entryDao.getById(it) }
    }

    /** `null` means "unrestricted so far", so the first set simply becomes it. */
    private fun intersect(current: Set<String>?, next: Set<String>): Set<String> =
        current?.intersect(next) ?: next
}
