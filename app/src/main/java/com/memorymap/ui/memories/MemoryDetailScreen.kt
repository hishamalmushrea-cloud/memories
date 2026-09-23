package com.memorymap.ui.memories

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.memorymap.ui.common.PhaseNote

/** Memory detail: media, text, place, emotion, audio, plus edit/delete/share. */
@Composable
fun MemoryDetailScreen(navController: NavHostController, memoryId: String) {
    PhaseNote(phase = "Phase 3")
}
