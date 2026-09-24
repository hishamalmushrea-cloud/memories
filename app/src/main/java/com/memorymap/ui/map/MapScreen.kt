package com.memorymap.ui.map

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.memorymap.ui.common.rememberPermissionRequest
import com.memorymap.util.LocationReader
import com.memorymap.util.MmLog
import kotlinx.coroutines.launch

/**
 * The home screen: an interactive map of everything the user placed somewhere.
 *
 * Location is read only when "I am here" is pressed, the tile credit is always
 * on screen because the tile hosts require it, and a cluster of many pins zooms
 * in rather than pretending to be one place.
 */
@Composable
fun MapScreen(
    navController: NavHostController,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var locating by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    val requestLocation = rememberPermissionRequest(Manifest.permission.ACCESS_FINE_LOCATION) {
        locating = true
        scope.launch {
            val position = runCatching { LocationReader.current(context) }
                .onFailure { MmLog.e("Unable to read the location", it) }
                .getOrNull()
            locating = false
            if (position == null) {
                notice = context.getString(R.string.map_location_unavailable)
            } else {
                viewModel.onUserLocation(position)
                notice = null
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        SlippyMap(
            provider = viewModel.provider,
            center = state.center,
            zoom = state.zoom,
            clusters = state.clusters,
            userLocation = state.userLocation,
            onCenterChange = viewModel::onCenterChange,
            onZoomChange = viewModel::onZoomChange,
            onClusterClick = { cluster ->
                val only = cluster.markers.singleOrNull()
                when {
                    only == null -> viewModel.zoomTo(cluster)
                    only.isMemory -> navController.navigate(Routes.memoryDetail(only.id))
                    else -> navController.navigate(
                        Routes.entryEditor(only.dateIso.orEmpty(), only.id),
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Tile credit. Open tile hosts require it to be visible on the map.
        Text(
            text = viewModel.provider.attribution,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp)
                .background(
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                    RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            FloatingActionButton(onClick = { navController.navigate(Routes.SEARCH) }) {
                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.action_search))
            }
            FloatingActionButton(onClick = requestLocation) {
                if (locating) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        Icons.Outlined.MyLocation,
                        contentDescription = stringResource(R.string.map_my_location),
                    )
                }
            }
        }

        notice?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        if (state.markerCount == 0 && !state.isLoading) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.map_no_pins),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        ExtendedFloatingActionButton(
            onClick = { navController.navigate(Routes.memoryEditor()) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            text = { Text(stringResource(R.string.map_add_memory_here)) },
        )
    }
}
