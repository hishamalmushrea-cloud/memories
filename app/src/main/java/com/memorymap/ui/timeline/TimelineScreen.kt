package com.memorymap.ui.timeline

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.memorymap.ui.common.PhaseNote

/**
 * The unified timeline of everything the user has recorded, with filters by type,
 * emotion, place and period. Implemented in Phase 7.
 */
@Composable
fun TimelineScreen(navController: NavHostController) {
    PhaseNote(phase = "Phase 7")
}
