package com.memorymap.domain.model

/**
 * Who is allowed to see a record.
 *
 * Diary entries are always created as [PRIVATE]; making something public is an
 * explicit, opt-in action and never a default.
 */
enum class Visibility {
    PRIVATE,
    SHARED,
    PUBLIC;

    companion object {
        fun fromName(value: String?): Visibility =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PRIVATE
    }
}
