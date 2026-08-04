package com.propdfeditor.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.propdf.editor.ui.PdfEditorScreen
import com.propdf.scanner.ui.ScannerScreen
import com.propdf.viewer.ui.IntegratedPDFViewerScreen
import com.propdfeditor.ui.filemanager.FileManagerScreen
import com.propdfeditor.ui.home.HomeDashboardScreen
import com.propdfeditor.ui.ocr.OcrHubScreen
import com.propdfeditor.ui.security.SecurityHubScreen
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
        // Home Dashboard
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

        // File Manager
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

        // PDF Viewer with deep links
        composable(
            route = "viewer/{uri}?page={page}",
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("page") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = "content://.*\\.pdf" },
                navDeepLink { uriPattern = "file://.*\\.pdf" }
            )
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            val page = backStackEntry.arguments?.getInt("page") ?: 0
            IntegratedPDFViewerScreen(
                documentUri = encodedUri.decode(),
                initialPage = page,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEditor = { uri ->
                    navController.navigate("editor/${uri.encode()}")
                },
                onNavigateToAnnotations = { uri ->
                    navController.navigate("viewer/${uri.encode()}?annotations=true")
                },
                onNavigateToShare = { uri ->
                    navController.navigate("share/${uri.encode()}")
                },
                onNavigateToSecurity = { uri ->
                    navController.navigate("security/${uri.encode()}")
                }
            )
        }

        // Annotation mode viewer
        composable(
            route = "viewer/{uri}?annotations={annotations}",
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("annotations") {
                    type = NavType.BoolType
                    defaultValue = false
                }
            )
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            val annotations = backStackEntry.arguments?.getBoolean("annotations") ?: false
            IntegratedPDFViewerScreen(
                documentUri = encodedUri.decode(),
                initialPage = 0,
                startInAnnotationMode = annotations,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEditor = { navController.navigate("editor/${it.encode()}") },
                onNavigateToAnnotations = { },
                onNavigateToShare = { navController.navigate("share/${it.encode()}") },
                onNavigateToSecurity = { navController.navigate("security/${it.encode()}") }
            )
        }

        // Scanner
        composable("scanner") {
            ScannerScreen(
                onPdfCreated = { uri ->
                    navController.navigate("viewer/${uri.encode()}") {
                        popUpTo("home") { inclusive = false }
                    }
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Tools Hub
        composable("tools") {
            ToolsHubScreen(
                onNavigateToCompression = { navController.navigate("compression") },
                onNavigateToOcr = { navController.navigate("ocr") },
                onNavigateToMerge = { navController.navigate("merge") },
                onNavigateToSplit = { navController.navigate("split") },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Settings
        composable("settings") {
            SettingsScreen(onNavigateBack = { navController.popBackStack() })
        }

        // PDF Editor
        composable(
            route = "editor/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            PdfEditorScreen(
                documentUri = encodedUri.decode(),
                onNavigateBack = { navController.popBackStack() },
                onSaveComplete = { uri ->
                    navController.navigate("viewer/${uri.encode()}")
