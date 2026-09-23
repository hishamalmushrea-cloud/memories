package com.memorymap.ui.map

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.memorymap.ui.common.PhaseNote

/**
 * Home screen. It will host the open-source map (Phase 5) with memory pins,
 * clustered markers, an "I am here" button and the filters.
 *
 * The `MapProvider` abstraction from the spec is introduced in Phase 5 so the
 * tile source can be swapped without touching the screens.
 */
@Composable
fun MapScreen(navController: NavHostController) {
    PhaseNote(phase = "Phase 5")
}
