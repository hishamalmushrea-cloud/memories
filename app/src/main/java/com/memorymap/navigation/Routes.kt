package com.memorymap.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.ui.graphics.vector.ImageVector
import com.memorymap.R

/**
 * Top-level destinations. Five tabs only: map, diary, memories, timeline,
 * account. "Nearby" is reachable from the map and the timeline rather than
 * taking a sixth tab.
 */
enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    MAP(Routes.MAP, R.string.tab_map, Icons.Outlined.Map),
    DIARY(Routes.DIARY, R.string.tab_diary, Icons.Outlined.DateRange),
    MEMORIES(Routes.MEMORIES, R.string.tab_memories, Icons.Outlined.PhotoLibrary),
    TIMELINE(Routes.TIMELINE, R.string.tab_timeline, Icons.Outlined.Timeline),
    PROFILE(Routes.PROFILE, R.string.tab_profile, Icons.Outlined.Person),
    ;

    companion object {
        val DEFAULT: TopLevelDestination = MAP
    }
}

/** Every route in the app, in one place so deep links stay consistent. */
object Routes {
    const val MAP = "map"
    const val DIARY = "diary"
    const val MEMORIES = "memories"
    const val TIMELINE = "timeline"
    const val PROFILE = "profile"
    const val NEARBY = "nearby"
    const val SEARCH = "search"
    const val CALENDAR = "calendar"
    const val SETTINGS = "settings"

    // Parameterised routes.
    const val DAY = "day/{date}"
    const val WEEK = "week/{date}"
    const val MONTH = "month/{year}/{month}"
    const val YEAR = "year/{year}"
    const val MEMORY_DETAIL = "memory/{memoryId}"
    const val MEMORY_EDITOR = "memory-editor?memoryId={memoryId}"
    const val ENTRY_EDITOR = "entry-editor?date={date}&entryId={entryId}"
    const val PLACE_DETAIL = "place/{placeId}"
    const val PERSON_DETAIL = "person/{personId}"

    fun day(date: String) = "day/$date"
    fun week(date: String) = "week/$date"
    fun month(year: Int, month: Int) = "month/$year/$month"
    fun year(year: Int) = "year/$year"
    fun memoryDetail(memoryId: String) = "memory/$memoryId"
    fun memoryEditor(memoryId: String? = null) = "memory-editor?memoryId=${memoryId.orEmpty()}"
    fun entryEditor(date: String, entryId: String? = null) = "entry-editor?date=$date&entryId=${entryId.orEmpty()}"
}
