package com.memorymap.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memorymap.R
import com.memorymap.domain.model.Emotion
import com.memorymap.domain.model.SearchResults
import com.memorymap.domain.usecase.DiaryTime
import com.memorymap.domain.usecase.SearchQuery
import com.memorymap.navigation.Routes
import com.memorymap.ui.common.emotionLabel
import com.memorymap.ui.common.formatLong
import com.memorymap.ui.common.rememberLocale
import java.util.Locale

/**
 * Search across the whole local archive.
 *
 * The line under the field says out loud what was understood from the words
 * typed. Structured search can only be trusted if the user can see the structure
 * it found, and correct it by typing something else.
 */
@Composable
fun SearchScreen(
    navController: NavHostController,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val locale = rememberLocale()
    val results = state.results

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = state.text,
            onValueChange = viewModel::onTextChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.search_field_label)) },
            placeholder = { Text(stringResource(R.string.search_field_example)) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = {
                if (state.text.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onTextChanged("") }) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = stringResource(R.string.search_clear),
                        )
                    }
                }
            },
        )

        ParsedIntent(query = results?.query, locale = locale)

        EmotionFilter(
            selected = state.filter.emotion,
            onSelect = viewModel::setEmotion,
            onClear = viewModel::clearFilter,
        )

        when {
            state.isSearching -> Hint(stringResource(R.string.search_running))
            results == null -> Hint(stringResource(R.string.search_explainer))
            results.isEmpty -> Hint(stringResource(R.string.search_no_results))
            else -> ResultList(results = results, locale = locale, navController = navController)
        }
    }
}

/**
 * What the parser made of the words, so a search that found nothing can be
 * understood rather than merely reported.
 */
@Composable
private fun ParsedIntent(query: SearchQuery?, locale: Locale) {
    if (query == null || query.isBlank) return

    val person = query.person?.let { stringResource(R.string.search_intent_person, it) }
    val place = query.place?.let { stringResource(R.string.search_intent_place, it) }

    val dateParts = mutableListOf<String>()
    query.day?.let { dateParts += it.toString() }
    query.month?.let { dateParts += DiaryTime.monthNames(locale)[it - 1] }
    query.year?.let { dateParts += it.toString() }
    val date = if (dateParts.isEmpty()) {
        null
    } else {
        stringResource(R.string.search_intent_date, dateParts.joinToString(" "))
    }

    val words = if (query.terms.isEmpty()) {
        null
    } else {
        stringResource(R.string.search_intent_words, query.terms.joinToString("، "))
    }

    val parts = listOfNotNull(person, place, date, words)
    if (parts.isEmpty()) return

    Text(
        text = parts.joinToString("  ·  "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmotionFilter(
    selected: Emotion?,
    onSelect: (Emotion?) -> Unit,
    onClear: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Emotion.entries.forEach { emotion ->
            FilterChip(
                selected = selected == emotion,
                onClick = { if (selected == emotion) onSelect(null) else onSelect(emotion) },
                label = { Text(emotionLabel(emotion)) },
            )
        }
        if (selected != null) {
            FilterChip(
                selected = false,
                onClick = onClear,
                label = { Text(stringResource(R.string.search_clear_filter)) },
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
    )
}

@Composable
private fun ResultList(
    results: SearchResults,
    locale: Locale,
    navController: NavHostController,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (results.people.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.search_section_people, results.people.size)) }
            items(results.people, key = { "person-${it.id}" }) { person ->
                ResultRow(
                    title = person.name,
                    subtitle = stringResource(R.string.search_open_person),
                    onClick = { navController.navigate(Routes.search("مع ${person.name}")) },
                )
            }
        }

        if (results.places.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.search_section_places, results.places.size)) }
            items(results.places, key = { "place-${it.id}" }) { place ->
                ResultRow(
                    title = place.name,
                    subtitle = stringResource(R.string.search_open_place),
                    onClick = { navController.navigate(Routes.search("في ${place.name}")) },
                )
            }
        }

        if (results.memories.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.search_section_memories, results.memories.size)) }
            items(results.memories, key = { "memory-${it.id}" }) { memory ->
                ResultRow(
                    title = memory.title,
                    subtitle = listOf(
                        memory.memoryDate.formatLong(locale),
                        emotionLabel(memory.emotion),
                    ).joinToString("  ·  "),
                    onClick = { navController.navigate(Routes.memoryDetail(memory.id)) },
                )
            }
        }

        if (results.entries.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.search_section_events, results.entries.size)) }
            items(results.entries, key = { "entry-${it.id}" }) { entry ->
                ResultRow(
                    title = entry.title,
                    subtitle = entry.date.formatLong(locale),
                    onClick = {
                        navController.navigate(
                            Routes.entryEditor(entry.date.toString(), entry.id),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun ResultRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
