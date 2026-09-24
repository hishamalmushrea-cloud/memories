package com.memorymap.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.memorymap.ui.diary.CalendarScreen
import com.memorymap.ui.diary.DiaryHomeScreen
import com.memorymap.ui.diary.EntryEditorScreen
import com.memorymap.ui.diary.MonthScreen
import com.memorymap.ui.diary.WeekScreen
import com.memorymap.ui.diary.YearScreen
import com.memorymap.ui.map.LocationPickerScreen
import com.memorymap.ui.map.MapScreen
import com.memorymap.ui.memories.MemoriesScreen
import com.memorymap.ui.memories.MemoryDetailScreen
import com.memorymap.ui.memories.MemoryEditorScreen
import com.memorymap.ui.nearby.NearbyScreen
import com.memorymap.ui.profile.ProfileScreen
import com.memorymap.ui.search.PeopleScreen
import com.memorymap.ui.search.PlacesScreen
import com.memorymap.ui.search.SearchScreen
import com.memorymap.ui.timeline.TimelineScreen

/**
 * The single navigation graph. Screens are addressed only through [Routes], so
 * renaming a destination is a one-line change.
 */
@Composable
fun MemoryMapNavHost(
    navController: NavHostController,
    startDestination: String = TopLevelDestination.DEFAULT.route,
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Routes.MAP) { MapScreen(navController) }
        composable(Routes.DIARY) { DiaryHomeScreen(navController) }
        composable(Routes.MEMORIES) { MemoriesScreen(navController) }
        composable(Routes.TIMELINE) { TimelineScreen(navController) }
        composable(Routes.PROFILE) { ProfileScreen(navController) }
        composable(Routes.NEARBY) { NearbyScreen(navController) }
        composable(
            route = Routes.SEARCH,
            arguments = listOf(
                navArgument("query") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { SearchScreen(navController) }
        composable(Routes.PEOPLE) { PeopleScreen(navController) }
        composable(Routes.PLACES) { PlacesScreen(navController) }

        composable(
            route = Routes.DAY,
            arguments = listOf(navArgument("date") { type = NavType.StringType }),
        ) { DayRoute(navController, it.arguments?.getString("date").orEmpty()) }

        composable(
            route = Routes.WEEK,
            arguments = listOf(navArgument("date") { type = NavType.StringType }),
        ) { entry -> WeekScreen(navController, entry.arguments?.getString("date").orEmpty()) }

        composable(
            route = Routes.MONTH,
            arguments = listOf(
                navArgument("year") { type = NavType.IntType },
                navArgument("month") { type = NavType.IntType },
            ),
        ) { entry ->
            MonthScreen(
                navController = navController,
                year = entry.arguments?.getInt("year") ?: 0,
                month = entry.arguments?.getInt("month") ?: 1,
            )
        }

        composable(
            route = Routes.YEAR,
            arguments = listOf(navArgument("year") { type = NavType.IntType }),
        ) { entry -> YearScreen(navController, entry.arguments?.getInt("year") ?: 0) }

        composable(
            route = Routes.MEMORY_DETAIL,
            arguments = listOf(navArgument("memoryId") { type = NavType.StringType }),
        ) { entry ->
            MemoryDetailScreen(navController, entry.arguments?.getString("memoryId").orEmpty())
        }

        composable(
            route = Routes.MEMORY_EDITOR,
            arguments = listOf(navArgument("memoryId") {
                type = NavType.StringType
                defaultValue = ""
            }),
        ) { MemoryEditorScreen(navController) }

        composable(
            route = Routes.ENTRY_EDITOR,
            arguments = listOf(
                navArgument("date") { type = NavType.StringType },
                navArgument("entryId") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { EntryEditorScreen(navController) }

        composable(Routes.CALENDAR) { CalendarScreen(navController) }

        composable(
            route = Routes.LOCATION_PICKER,
            arguments = listOf(
                navArgument("lat") {
                    type = NavType.StringType
                    defaultValue = ""
                },
                navArgument("lon") {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { LocationPickerScreen(navController) }
    }
}

/**
 * The day screen is the heart of the diary, so it gets its own file and a
 * real ViewModel in Phase 4; this keeps the route wired from Phase 1.
 */
@Composable
private fun DayRoute(navController: NavHostController, date: String) {
    com.memorymap.ui.diary.DayScreen(navController = navController, date = date)
}
