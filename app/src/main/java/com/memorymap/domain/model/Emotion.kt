package com.memorymap.domain.model

/**
 * The six emotions supported by the app. Each one owns an explicit brand color
 * so the map, timeline and diary can stay visually consistent.
 *
 * Colors are stored as ARGB hex strings to keep this module free of Android
 * framework types, which keeps it unit-testable on the JVM.
 */
enum class Emotion(val colorHex: String) {
    HAPPY("#FFC107"),
    SAD("#2196F3"),
    LOVE("#E91E63"),
    FEAR("#9C27B0"),
    PRIDE("#FF9800"),
    NOSTALGIA("#795548");

    companion object {
        /** Lenient parser used when reading rows written by older builds. */
        fun fromName(value: String?): Emotion? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
