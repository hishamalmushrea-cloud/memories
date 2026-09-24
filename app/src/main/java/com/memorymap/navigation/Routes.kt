package com.memorymap.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Timeline
import android.net.Uri
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
    const val SEARCH = "search?query={query}"
    const val PEOPLE = "people"
    const val PLACES = "places"
    const val CALENDAR = "calendar"
    const val BACKUP = "backup"

    // Parameterised routes.
    const val DAY = "day/{date}"
    const val WEEK = "week/{date}"
    const val MONTH = "month/{year}/{month}"
    const val YEAR = "year/{year}"
    const val MEMORY_DETAIL = "memory/{memoryId}"
    const val MEMORY_EDITOR = "memory-editor?memoryId={memoryId}"
    const val ENTRY_EDITOR = "entry-editor?date={date}&entryId={entryId}"
    const val PLACE_DETAIL = "place/{placeId}"
    const val LOCATION_PICKER = "location-picker?lat={lat}&lon={lon}"
    const val PERSON_DETAIL = "person/{personId}"

    /**
     * Search, optionally pre-filled.
     *
     * People and places link here with their own pattern - `مع أحمد`, `في صنعاء` -
     * so one screen shows every kind of result instead of three screens showing
     * one each. The value is encoded because a name can contain a space.
     */
    fun search(query: String? = null) = "search?query=${Uri.encode(query.orEmpty())}"

    fun day(date: String) = "day/$date"
    fun week(date: String) = "week/$date"
    fun month(year: Int, month: Int) = "month/$year/$month"
    fun year(year: Int) = "year/$year"
    fun memoryDetail(memoryId: String) = "memory/$memoryId"
    fun memoryEditor(memoryId: String? = null) = "memory-editor?memoryId=${memoryId.orEmpty()}"
    fun entryEditor(date: String, entryId: String? = null) = "entry-editor?date=$date&entryId=${entryId.orEmpty()}"

    /** Manual location pick; the caller's coordinates pre-centre the map. */
    fun locationPicker(latitude: Double? = null, longitude: Double? = null) =
        "location-picker?lat=${latitude?.toString().orEmpty()}&lon=${longitude?.toString().orEmpty()}"

    /** Key the location picker writes its result under. */
    const val RESULT_LOCATION = "pickedLocation"

    /** Decodes a `lat,lon` result written by the location picker. */
    fun parseLocation(raw: String?): Pair<Double, Double>? {
        val parts = raw?.split(",") ?: return null
        if (parts.size != 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        return lat to lon
    }
}
