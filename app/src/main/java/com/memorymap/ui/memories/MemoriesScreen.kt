package com.memorymap.ui.memories

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.memorymap.ui.common.PhaseNote

/**
 * The memory list. Create / edit / delete plus photo, audio and video
 * attachments arrive in Phase 3; this screen is intentionally empty rather than
 * filled with invented records.
 */
@Composable
fun MemoriesScreen(navController: NavHostController) {
    PhaseNote(phase = "Phase 3")
}
