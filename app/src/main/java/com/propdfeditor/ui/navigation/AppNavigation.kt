package com.propdfeditor.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import com.propdf.viewer.ui.PDFViewerScreen
import com.propdfeditor.ui.home.HomeDashboardScreen
import com.propdfeditor.ui.filemanager.FileManagerScreen
import com.propdfeditor.ui.scanner.ScannerLauncherScreen
import com.propdfeditor.ui.settings.SettingsScreen
import com.propdfeditor.ui.tools.ToolsHubScreen

@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = "home"
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        composable("home") {
            HomeDashboardScreen(
                onOpenFile = { uri ->
                    navController.navigate("viewer/${uri.encode()}") {
                        launchSingleTop = true
                    }
                },
                onNavigateToFileManager = { navController.navigate("files") },
                onNavigateToScanner = { navController.navigate("scanner") },
                onNavigateToTools = { navController.navigate("tools") },
                onNavigateToSettings = { navController.navigate("settings") },
                onContinueReading = { uri, page ->
                    navController.navigate("viewer/${uri.encode()}?page=$page") {
                        launchSingleTop = true
                    }
                }
            )
        }

        composable("files") {
            FileManagerScreen(
                onOpenPdf = { uri ->
                    navController.navigate("viewer/${uri.encode()}") {
                        popUpTo("home") { inclusive = false }
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "viewer/{uri}?page={page}",
            deepLinks = listOf(
                navDeepLink { uriPattern = "content://.*\\.pdf" },
                navDeepLink { uriPattern = "file://.*\\.pdf" }
            )
        ) { backStackEntry ->
            val decodedUri = (backStackEntry.arguments?.getString("uri") ?: "").decode()
            PDFViewerScreen(
                documentUri = android.net.Uri.parse(decodedUri),
                documentId = decodedUri
            )
        }

        composable("scanner") {
            ScannerLauncherScreen(
                onPdfCreated = { uri ->
                    navController.navigate("viewer/${uri.encode()}") {
                        popUpTo("home") { inclusive = false }
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("tools") {
            ToolsHubScreen(
                onNavigateToCompression = { navController.navigate("compression") },
                onNavigateToOcr = { navController.navigate("ocr") },
                onNavigateToMerge = { navController.navigate("merge") },
                onNavigateToSplit = { navController.navigate("split") },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable("settings") {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }

        // Placeholder routes for module integration — wire to actual module screens
        composable("editor/{uri}") { /* Editor module entry */ }
        composable("annotate/{uri}") { /* Annotation module entry */ }
        composable("share/{uri}") { /* Share module entry */ }
        composable("security/{uri}") { /* Security module entry */ }
        composable("compression") { /* Compression module entry */ }
        composable("ocr") { /* OCR module entry */ }
        composable("merge") { /* Editor merge entry */ }
        composable("split") { /* Editor split entry */ }
    }
}

private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8")
private fun String.decode(): String = java.net.URLDecoder.decode(this, "UTF-8")
