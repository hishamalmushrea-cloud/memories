package com.memorymap.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale
import com.memorymap.ui.common.rememberLocale

private val LightColors = lightColorScheme(
    primary = MemoryMapColors.Primary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = MemoryMapColors.PrimaryLight,
    onPrimaryContainer = androidx.compose.ui.graphics.Color.White,
    secondary = MemoryMapColors.Secondary,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = MemoryMapColors.SecondaryLight,
    onSecondaryContainer = androidx.compose.ui.graphics.Color.White,
    surface = MemoryMapColors.SurfaceLight,
    background = MemoryMapColors.SurfaceLight,
)

private val DarkColors = darkColorScheme(
    primary = MemoryMapColors.PrimaryLight,
    primaryContainer = MemoryMapColors.PrimaryDark,
    secondary = MemoryMapColors.SecondaryLight,
    secondaryContainer = MemoryMapColors.SecondaryDark,
    surface = MemoryMapColors.SurfaceDark,
    background = MemoryMapColors.SurfaceDark,
)

/**
 * App theme. Light and dark are both supported; dynamic color is opt-in because
 * the brand identity (deep indigo + purple) is part of the product.
 *
 * RTL is handled by the layout system: the app declares `supportsRtl="true"` and
 * Arabic is the default locale, so every screen mirrors automatically.
 */
@Composable
fun MemoryMapTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val locale = rememberLocale()

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = remember(locale) { memoryMapTypography(locale) },
        content = content,
    )
}
