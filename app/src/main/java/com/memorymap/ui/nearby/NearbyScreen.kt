package com.memorymap.ui.nearby

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.memorymap.ui.common.PhaseNote

/**
 * "Nearby": memories and events within 1 km of a location the user asks for.
 * Location is only read on demand, never tracked in the background.
 */
@Composable
fun NearbyScreen(navController: NavHostController) {
    PhaseNote(phase = "Phase 5")
}
