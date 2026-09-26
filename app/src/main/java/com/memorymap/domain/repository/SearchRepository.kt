package com.memorymap.domain.repository

import com.memorymap.domain.model.SearchFilter
import com.memorymap.domain.model.SearchResults
import com.memorymap.domain.usecase.SearchQuery

/**
 * Search across the local archive.
 *
 * Structured text search only — the specification rules out any model
 * interpreting the query. Everything is a `LIKE` against a column the user
 * wrote, plus the date and emotion filters, which is why the results can always
 * be explained by pointing at the row that matched.
 *
 * Local only on purpose: searching must work on a plane, and sending what the
 * user typed to a server would leak the subject of their diary.
 */
interface SearchRepository {

    suspend fun search(
        userId: String,
        query: SearchQuery,
        filter: SearchFilter = SearchFilter(),
    ): SearchResults
}
