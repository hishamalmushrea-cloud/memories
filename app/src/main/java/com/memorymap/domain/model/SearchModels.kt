package com.memorymap.domain.model

import com.memorymap.domain.usecase.SearchQuery

/**
 * What the user narrowed the search to, beyond the words they typed.
 *
 * Deliberately small: a date window and an emotion. Anything larger stops being
 * a filter and starts being a second search screen.
 */
data class SearchFilter(
    val emotion: Emotion? = null,
) {
    val isUnconstrained: Boolean get() = emotion == null
}

/**
 * Everything the local database had to offer, grouped by what it is.
 *
 * People and places are returned as well as searched: a result of
 * "صنعاء" that says *there is a place called صنعاء, here is everything in it* is
 * more useful than a list of rows that happen to contain the word.
 */
data class SearchResults(
    val query: SearchQuery,
    val memories: List<Memory> = emptyList(),
    val entries: List<DailyEntry> = emptyList(),
    val people: List<Person> = emptyList(),
    val places: List<Place> = emptyList(),
) {
    val recordCount: Int get() = memories.size + entries.size
    val total: Int get() = recordCount + people.size + places.size
    val isEmpty: Boolean get() = total == 0
}
