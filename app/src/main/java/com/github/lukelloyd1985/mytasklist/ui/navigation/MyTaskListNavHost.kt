package com.github.lukelloyd1985.mytasklist.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.github.lukelloyd1985.mytasklist.DeepLinkTarget
import com.github.lukelloyd1985.mytasklist.ui.listdetail.ListDetailScreen
import com.github.lukelloyd1985.mytasklist.ui.listdetail.ListSettingsScreen
import com.github.lukelloyd1985.mytasklist.ui.lists.ListsScreen
import com.github.lukelloyd1985.mytasklist.ui.profile.ProfileScreen

@Composable
fun MyTaskListNavHost(deepLink: DeepLinkTarget? = null, onDeepLinkConsumed: () -> Unit = {}) {
    val navController = rememberNavController()

    // Fires whenever a new deep link arrives (notification tap while the
    // app is already running re-triggers this via MainActivity.onNewIntent
    // updating its deepLink state) - navigates straight to the relevant
    // list (and, if set, opens that task's editor once ListDetailScreen
    // loads - see its own initialTaskId handling).
    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            navController.navigate(Destinations.listDetail(deepLink.listId, deepLink.taskId)) {
                popUpTo(Destinations.LISTS)
            }
            onDeepLinkConsumed()
        }
    }

    NavHost(navController = navController, startDestination = Destinations.LISTS) {
        composable(Destinations.LISTS) {
            ListsScreen(
                onOpenList = { listId -> navController.navigate(Destinations.listDetail(listId)) },
                onOpenProfile = { navController.navigate(Destinations.PROFILE) },
            )
        }

        composable(
            Destinations.LIST_DETAIL,
            arguments = listOf(
                navArgument("listId") { type = NavType.StringType },
                navArgument("taskId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            ListDetailScreen(
                initialTaskId = backStackEntry.arguments?.getString("taskId"),
                onBack = { navController.popBackStack() },
                onOpenSettings = { listId -> navController.navigate(Destinations.listSettings(listId)) },
            )
        }

        composable(
            Destinations.LIST_SETTINGS,
            arguments = listOf(navArgument("listId") { type = NavType.StringType }),
        ) {
            ListSettingsScreen(
                onBack = { navController.popBackStack() },
                onListDeleted = { navController.popBackStack(Destinations.LISTS, inclusive = false) },
            )
        }

        composable(Destinations.PROFILE) {
            ProfileScreen(onBack = { navController.popBackStack() })
        }
    }
}
