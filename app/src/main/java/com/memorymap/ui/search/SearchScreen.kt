package com.memorymap.ui.search

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.memorymap.ui.common.PhaseNote

/**
 * Local search across memory titles and bodies, event titles and bodies, people
 * and place names, and dates. Structured text search only, no AI. Phase 7.
 */
@Composable
fun SearchScreen(navController: NavHostController) {
    PhaseNote(phase = "Phase 7")
}
