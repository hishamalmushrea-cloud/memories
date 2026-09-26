package com.memorymap.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memorymap.R
import com.memorymap.domain.model.GeoPoint
import com.memorymap.navigation.Routes

/**
 * The people in the archive.
 *
 * Opening a name runs a search for it rather than showing a second, separate
 * list screen: one place renders every kind of result, and a person's records
 * are exactly what `مع <name>` finds.
 */
@Composable
fun PeopleScreen(
    navController: NavHostController,
    viewModel: OrganizationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.people_title), style = MaterialTheme.typography.headlineSmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.draftName,
                onValueChange = viewModel::onDraftNameChanged,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(stringResource(R.string.people_add_label)) },
            )
            IconButton(onClick = viewModel::addPerson, enabled = state.draftName.isNotBlank()) {
                Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.people_add_action))
            }
        }

        if (state.people.isEmpty()) {
            EmptyHint(stringResource(R.string.people_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.people, key = { it.id }) { person ->
                    Card(
                        onClick = { navController.navigate(Routes.search("مع ${person.name}")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(person.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = stringResource(
                                        R.string.reference_record_count,
                                        state.personCounts[person.id] ?: 0,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.deletePerson(person.id) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.people_delete),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The places in the archive.
 *
 * A place is a name *and* a position, so adding one asks for both: without
 * coordinates it could not be shown on the map, which is half of what a place is
 * for.
 */
@Composable
fun PlacesScreen(
    navController: NavHostController,
    viewModel: OrganizationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ready = state.draftName.isNotBlank() && state.draftLocation != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.places_title), style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = state.draftName,
            onValueChange = viewModel::onDraftNameChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.places_add_label)) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val current = state.draftLocation
                    navController.navigate(Routes.locationPicker(current?.latitude, current?.longitude))
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Outlined.MyLocation, contentDescription = null)
                Text(
                    text = state.draftLocation?.let { formatCoordinates(it) }
                        ?: stringResource(R.string.places_pick_location),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            OutlinedButton(onClick = viewModel::addPlace, enabled = ready) {
                Text(stringResource(R.string.places_add_action))
            }
        }

        if (state.places.isEmpty()) {
            EmptyHint(stringResource(R.string.places_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.places, key = { it.id }) { place ->
                    Card(
                        onClick = { navController.navigate(Routes.search("في ${place.name}")) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(place.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = listOf(
                                        formatCoordinates(place.location),
                                        stringResource(
                                            R.string.reference_record_count,
                                            state.placeCounts[place.id] ?: 0,
                                        ),
                                    ).joinToString("  ·  "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.deletePlace(place.id) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.places_delete),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Three decimals is about a hundred metres, which is all a label needs. */
private fun formatCoordinates(point: GeoPoint): String =
    "%.3f, %.3f".format(point.latitude, point.longitude)

@Composable
private fun EmptyHint(text: String) {
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
