package com.propdfeditor.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import com.propdf.editor.ui.settings.SettingsScreen
import com.propdfeditor.ui.tools.ToolsHubScreen
import com.propdfeditor.ui.share.ShareSheetScreen
import com.propdfeditor.ui.compression.CompressionScreen
import com.propdfeditor.ui.merge.MergeScreen
import com.propdfeditor.ui.split.SplitScreen

@Composable
fun AppNavigation(
    navController: androidx.navigation.NavHostController,
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
                    navController.navigate("annotate/${uri.encode()}")
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
            route = "annotate/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            IntegratedPDFViewerScreen(
                documentUri = encodedUri.decode(),
                initialPage = 0,
                startInAnnotationMode = true,
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
            SettingsScreen(navController = navController)
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
                    navController.navigate("viewer/${uri.encode()}") {
                        popUpTo("home") { inclusive = false }
                    }
                }
            )
        }

        // Security Hub
        composable(
            route = "security/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            SecurityHubScreen(
                documentUri = encodedUri.decode(),
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Share
        composable(
            route = "share/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            ShareSheetScreen(
                documentUri = encodedUri.decode(),
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // OCR Hub
        composable("ocr") {
            OcrHubScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Compression
        composable("compression") {
            CompressionScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Merge
        composable("merge") {
            MergeScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Split
        composable("split") {
            SplitScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

private fun String.encode(): String = java.net.URLEncoder.encode(this, "UTF-8")
private fun String.decode(): String = java.net.URLDecoder.decode(this, "UTF-8")
