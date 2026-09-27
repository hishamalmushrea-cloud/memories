package com.memorymap.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand colors from the product spec.
 *
 * ```text
 * Primary:   #1A237E
 * Secondary: #6A1B9A
 * ```
 */
object MemoryMapColors {
    val Primary = Color(0xFF1A237E)
    val Secondary = Color(0xFF6A1B9A)

    val PrimaryLight = Color(0xFF534BAE)
    val PrimaryDark = Color(0xFF000051)
    val SecondaryLight = Color(0xFF9C4ACC)
    val SecondaryDark = Color(0xFF38006B)

    val SurfaceLight = Color(0xFFFBF8FF)
    val SurfaceDark = Color(0xFF131218)

    // Emotion palette, identical in both themes so a color always means the same
    // feeling wherever it appears (map pin, timeline dot, diary chip).
    val Happy = Color(0xFFFFC107)
    val Sad = Color(0xFF2196F3)
    val Love = Color(0xFFE91E63)
    val Fear = Color(0xFF9C27B0)
    val Pride = Color(0xFFFF9800)
    val Nostalgia = Color(0xFF795548)
}
