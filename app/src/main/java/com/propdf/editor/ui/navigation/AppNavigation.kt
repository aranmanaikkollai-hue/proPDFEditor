package com.propdf.editor.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.propdf.editor.ui.home.HomeScreen
import com.propdf.editor.ui.main.MainViewModel
import com.propdf.editor.ui.tools.ToolsScreen
import com.propdf.editor.ui.tools.page.PageEditorScreen
import com.propdf.editor.ui.viewer.ViewerScreen

@Composable
fun AppNavigation(
    navController: NavHostController,
    mainViewModel: MainViewModel
) {
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {
            HomeScreen(
                navController = navController,
                mainViewModel = mainViewModel,
                onOpenPdf = { /* handled by MainActivity */ }
            )
        }

        composable("viewer/{uri}") { backStackEntry ->
            val uriString = backStackEntry.arguments?.getString("uri") ?: return@composable
            ViewerScreen(
                uri = Uri.parse(uriString),
                onNavigateBack = { navController.navigateUp() }
            )
        }

        composable("tools") {
            ToolsScreen(
                navController = navController,
                onOpenPdfPicker = {
                    mainViewModel.openPdfPicker()
                }
            )
        }

        composable(
            "page_editor/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val uriString = backStackEntry.arguments?.getString("uri") ?: return@composable
            PageEditorScreen(
                pdfUri = Uri.parse(uriString),
                onNavigateBack = { navController.navigateUp() },
                onOpenPdf = { uri ->
                    navController.navigate("viewer/${Uri.encode(uri.toString())}")
                }
            )
        }

        // Additional tool routes can be added here
    }
}
