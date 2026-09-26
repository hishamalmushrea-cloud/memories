package com.memorymap.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.memorymap.R
import com.memorymap.navigation.Routes
import java.util.Locale

/**
 * Picks a location by hand: move the map until the pin is where you mean, then
 * confirm.
 *
 * The result goes back through the navigation stack's saved state rather than a
 * shared object, so the editor that asked for it is the only one that sees it.
 * The tile credit stays visible here too.
 */
@Composable
fun LocationPickerScreen(
    navController: NavHostController,
    viewModel: LocationPickerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize()) {
        SlippyMap(
            provider = viewModel.provider,
            center = state.center,
            zoom = state.zoom,
            clusters = emptyList(),
            userLocation = null,
            onCenterChange = viewModel::onCenterChange,
            onZoomChange = viewModel::onZoomChange,
            onClusterClick = {},
            modifier = Modifier.fillMaxSize(),
        )

        // The pin is fixed at the centre; the map moves under it.
        Icon(
            imageVector = Icons.Outlined.Place,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .align(Alignment.Center)
                .size(40.dp)
                .shadow(4.dp),
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_cancel),
                )
            }
            Text(
                text = stringResource(R.string.location_picker_title),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Text(
            text = viewModel.provider.attribution,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 60.dp, end = 8.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.location_picker_coordinates,
                    String.format(Locale.US, "%.5f", state.center.latitude),
                    String.format(Locale.US, "%.5f", state.center.longitude),
                ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { navController.popBackStack() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
                Button(
                    onClick = {
                        val picked = viewModel.picked
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set(Routes.RESULT_LOCATION, "${picked.latitude},${picked.longitude}")
                        navController.popBackStack()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.location_picker_confirm))
                }
            }
        }
    }
}
