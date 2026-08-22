package com.mytasks.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mytasks.app.ui.listdetail.ListDetailScreen
import com.mytasks.app.ui.listdetail.ListSettingsScreen
import com.mytasks.app.ui.lists.ListsScreen
import com.mytasks.app.ui.profile.ProfileScreen

@Composable
fun MyTasksNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Destinations.LISTS) {
        composable(Destinations.LISTS) {
            ListsScreen(
                onOpenList = { listId -> navController.navigate(Destinations.listDetail(listId)) },
                onOpenProfile = { navController.navigate(Destinations.PROFILE) },
            )
        }

        composable(
            Destinations.LIST_DETAIL,
            arguments = listOf(navArgument("listId") { type = NavType.StringType }),
        ) {
            ListDetailScreen(
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
