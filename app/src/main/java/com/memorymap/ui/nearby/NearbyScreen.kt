package com.memorymap.ui.nearby

import android.Manifest
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memorymap.R
import com.memorymap.navigation.Routes
import com.memorymap.ui.common.rememberLocale
import com.memorymap.ui.common.rememberPermissionRequest
import com.memorymap.util.Geo
import com.memorymap.util.LocationReader
import com.memorymap.util.MmLog
import kotlinx.coroutines.launch

/**
 * "Near by": the memories and events within a kilometre of a position the user
 * asks for.
 *
 * The position is read once, when the button is pressed, and is never refreshed
 * on its own: there is no background tracking and nothing is recorded about where
 * the user is. The radius and the distance of each record are shown, so the list
 * explains itself rather than being a pile of unrelated rows.
 */
@Composable
fun NearbyScreen(
    navController: NavHostController,
    viewModel: NearbyViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locale = rememberLocale()
    var locating by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val requestLocation = rememberPermissionRequest(Manifest.permission.ACCESS_FINE_LOCATION) {
        locating = true
        notice = null
        scope.launch {
            val position = runCatching { LocationReader.current(context) }
                .onFailure { MmLog.e("Unable to read the location", it) }
                .getOrNull()
            locating = false
            if (position == null) {
                notice = context.getString(R.string.map_location_unavailable)
                viewModel.onLocationFailed()
            } else {
                viewModel.onLocated(position)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_cancel),
                )
            }
            Text(
                text = stringResource(R.string.nearby_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = requestLocation, enabled = !locating) {
                Icon(
                    Icons.Outlined.MyLocation,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.map_my_location))
            }
        }

        notice?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        when {
            locating -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.nearby_locating))
                }
            }

            state.origin == null -> Text(
                text = stringResource(R.string.nearby_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            else -> {
                Text(
                    text = stringResource(
                        R.string.nearby_within,
                        Geo.formatDistance(state.radiusMeters, locale),
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )

                if (state.items.isEmpty()) {
                    Text(
                        text = stringResource(
                            if (state.locatedCount == 0) {
                                R.string.nearby_nothing_located
                            } else {
                                R.string.nearby_none_within
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.items, key = { "${it.isMemory}-${it.id}" }) { item ->
                            NearbyRow(
                                item = item,
                                distance = Geo.formatDistance(item.distanceMeters, locale),
                                onClick = {
                                    if (item.isMemory) {
                                        navController.navigate(Routes.memoryDetail(item.id))
                                    } else {
                                        navController.navigate(
                                            Routes.entryEditor(item.dateIso, item.id),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NearbyRow(
    item: NearbyItem,
    distance: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (item.isMemory) {
                    Icons.Outlined.PhotoLibrary
                } else {
                    Icons.Outlined.Schedule
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = item.dateIso,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = distance,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
