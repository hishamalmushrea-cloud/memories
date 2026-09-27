package com.memorymap.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The locale the UI should render in.
 *
 * [LocalConfiguration.current] is a @Composable read, so it must be captured in
 * the composition and only then used inside the non-composable `remember`
 * calculation. Reading `.current` inside that lambda does not compile.
 */
@Composable
fun rememberLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        configuration.locales?.takeUnless { it.isEmpty }?.get(0) ?: Locale.getDefault()
    }
}
