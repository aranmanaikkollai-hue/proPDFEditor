package com.propdfeditor.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.propdf.editor.ui.PdfEditorScreen
import com.propdf.editor.ui.tools.page.PageEditorScreen
import com.propdf.scanner.ui.ScannerScreen
import com.propdf.viewer.ui.IntegratedPDFViewerScreen
import com.propdfeditor.ui.filemanager.FileManagerScreen
import com.propdfeditor.ui.home.HomeDashboardScreen
import com.propdfeditor.ui.ocr.OcrHubScreen
import com.propdfeditor.ui.security.SecurityHubScreen
import com.propdfeditor.ui.security.RedactionScreen
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
    startDestination: String = "home",
    pendingExternalUri: String? = null,
    onExternalUriConsumed: () -> Unit = {}
) {
    // -------------------------------------------------------------------------
    // EXTERNAL PDF INTENT HANDLING
    // When MainActivity receives an ACTION_VIEW / ACTION_SEND intent with a
    // PDF URI, it stores the URI in pendingExternalUri. This LaunchedEffect
    // navigates to the viewer once, then notifies MainActivity to clear the
    // pending URI so it is not re-processed on recomposition.
    // -------------------------------------------------------------------------
    LaunchedEffect(pendingExternalUri) {
        pendingExternalUri?.let { uri ->
            val currentRoute = navController.currentDestination?.route
            // Avoid duplicate navigation if already on viewer
            if (currentRoute != "viewer/{uri}?page={page}") {
                navController.navigate("viewer/${uri.encode()}?page=0") {
                    popUpTo("home") { inclusive = false }
                    launchSingleTop = true
                }
            }
            onExternalUriConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier
    ) {
        // =====================================================================
        // Home Dashboard
        // =====================================================================
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

        // =====================================================================
        // File Manager
        // =====================================================================
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

        // =====================================================================
        // PDF Viewer
        // =====================================================================
        // CRITICAL FIX: Removed invalid deep-link declarations.
        //
        // The previous configuration contained:
        //   deepLinks = listOf(
        //       navDeepLink { uriPattern = "content://.*\\.pdf" },
        //       navDeepLink { uriPattern = "file://.*\\.pdf" }
        //   )
        //
        // These patterns are regex wildcards (.*) and do NOT provide a named
        // {uri} argument that matches the route argument "uri". Jetpack
        // Navigation validates this at graph-build time and throws:
        //   IllegalArgumentException:
        //   "Deep link content://.*\\.pdf can't be used to open destination
        //    viewer/{uri}?page={page}"
        //
        // This caused a FATAL STARTUP CRASH on every launch — the NavHost
        // could not be constructed, so Home was never reached.
        //
        // External PDF opening is now handled in MainActivity via
        // Intent.ACTION_VIEW / ACTION_SEND intent filters, then passed safely
        // through pendingExternalUri → LaunchedEffect → navController.navigate().
        // =====================================================================
        composable(
            route = "viewer/{uri}?page={page}",
            arguments = listOf(
                navArgument("uri") { type = NavType.StringType },
                navArgument("page") {
                    type = NavType.IntType
                    defaultValue = 0
                }
            )
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            val page = backStackEntry.arguments?.getInt("page") ?: 0
            IntegratedPDFViewerScreen(
                documentUri = encodedUri, // already decoded once by Navigation Compose itself
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

        // =====================================================================
        // Annotation mode viewer
        // =====================================================================
        composable(
            route = "annotate/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            IntegratedPDFViewerScreen(
                documentUri = encodedUri, // already decoded once by Navigation Compose itself
                initialPage = 0,
                startInAnnotationMode = true,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEditor = { navController.navigate("editor/${it.encode()}") },
                onNavigateToAnnotations = { },
                onNavigateToShare = { navController.navigate("share/${it.encode()}") },
                onNavigateToSecurity = { navController.navigate("security/${it.encode()}") }
            )
        }

        // =====================================================================
        // Scanner
        // =====================================================================
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

        // =====================================================================
        // Tools Hub
        // =====================================================================
        composable("tools") {
            ToolsHubScreen(
                onNavigateToCompression = { navController.navigate("compression") },
                onNavigateToOcr = { navController.navigate("ocr") },
                onNavigateToMerge = { navController.navigate("merge") },
                onNavigateToSplit = { navController.navigate("split") },
                onNavigateToSecurity = { uri -> navController.navigate("security/${uri.toString().encode()}") },
                onNavigateToPageEditor = { uri -> navController.navigate("page-editor/${uri.toString().encode()}") },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // =====================================================================
        // Settings
        // =====================================================================
        composable("settings") {
            SettingsScreen(navController = navController)
        }

        // =====================================================================
        // PDF Editor
        // =====================================================================
        composable(
            route = "editor/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            PdfEditorScreen(
                documentUri = encodedUri, // already decoded once by Navigation Compose itself
                onNavigateBack = { navController.popBackStack() },
                onSaveComplete = { uri ->
                    navController.navigate("viewer/${uri.encode()}") {
                        popUpTo("home") { inclusive = false }
                    }
                },
                onNavigateToMerge = { navController.navigate("merge") },
                onNavigateToSplit = { navController.navigate("split") }
            )
        }

        // =====================================================================
        // Page Editor (organize/crop/resize/insert/reorder -- full page-management
        // workflow backed by PdfOperationsRepository via PdfOperationWorker). This
        // screen and its ViewModel were fully built and wired to the real repository
        // but were never registered in the nav graph or reachable from anywhere in the
        // app -- Compress/OCR/Merge/Split had entries in the Tools Hub, this didn't.
        // =====================================================================
        composable(
            route = "page-editor/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            PageEditorScreen(
                pdfUri = android.net.Uri.parse(encodedUri), // already decoded once by Navigation Compose itself
                onNavigateBack = { navController.popBackStack() },
                onOpenPdf = { uri ->
                    navController.navigate("viewer/${uri.toString().encode()}") {
                        popUpTo("home") { inclusive = false }
                    }
                }
            )
        }

        // =====================================================================
        // Security Hub
        // =====================================================================
        composable(
            route = "security/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            SecurityHubScreen(
                documentUri = encodedUri, // already decoded once by Navigation Compose itself
                onNavigateBack = { navController.popBackStack() },
                onNavigateToRedact = { uri -> navController.navigate("redact/${uri.encode()}") }
            )
        }

        // =====================================================================
        // Redaction (interactive page-rect marking, backed by SecurityRepository's
        // real redaction engine + RedactionOverlayView, both previously dormant --
        // see RedactionScreen/RedactionViewModel)
        // =====================================================================
        composable(
            route = "redact/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            RedactionScreen(
                documentUri = encodedUri, // already decoded once by Navigation Compose itself
                onNavigateBack = { navController.popBackStack() },
                onRedactionComplete = { uri ->
                    navController.navigate("viewer/${uri.encode()}") {
                        popUpTo("home") { inclusive = false }
                    }
                }
            )
        }

        // =====================================================================
        // Share
        // =====================================================================
        composable(
            route = "share/{uri}",
            arguments = listOf(navArgument("uri") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
            ShareSheetScreen(
                documentUri = encodedUri, // already decoded once by Navigation Compose itself
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // =====================================================================
        // OCR Hub
        // =====================================================================
        composable("ocr") {
            OcrHubScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // =====================================================================
        // Compression
        // =====================================================================
        composable("compression") {
            CompressionScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // =====================================================================
        // Merge
        // =====================================================================
        composable("merge") {
            MergeScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // =====================================================================
        // Split
        // =====================================================================
        composable("split") {
            SplitScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

// Navigation Compose (2.7.7) implements every composable() route as an
// implicit deep link internally: when it matches the navigated route string
// against the declared pattern (e.g. "viewer/{uri}?page={page}"), it parses
// the route as a real android.net.Uri and applies Uri.decode() to each
// captured path-segment argument automatically before it ever reaches
// backStackEntry.arguments. That means the value returned by
// arguments?.getString("uri") is ALREADY decoded once by Navigation itself.
//
// This file used to encode with java.net.URLEncoder (form/application-x-www-
// form-urlencoded semantics: spaces -> '+', and -- critically -- it also
// re-escapes any '%' that was already present in the URI, since URLEncoder
// has no idea the input is itself a URI) and then manually called
// java.net.URLDecoder.decode() a SECOND time after extracting the argument.
// For a simple "content://authority/document/123" URI this round-trip
// happened to cancel out, but real-world SAF/Downloads-provider URIs
// routinely contain their own embedded percent-encoded segments (e.g.
// ".../document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Ffile.pdf"). For
// those, encoding with URLEncoder double-escaped the existing '%' characters,
// Navigation's automatic single decode pass only unwound one layer of that,
// and the subsequent manual URLDecoder.decode() then incorrectly decoded the
// URI's own embedded %3A/%2F segments (which are not supposed to be touched
// again), producing a string that no longer matched the exact Uri the app
// held a persisted/transient read permission for -- SecurityException / "File
// access has expired" on first open, even though the URI had just been picked
// and granted moments earlier. Re-picking via "Choose PDF Again" appeared to
// "fix" it only because that path sets the Uri directly from the picker's
// ActivityResult in memory, bypassing this nav-route round trip entirely.
//
// Fix: encode with android.net.Uri.encode() (the counterpart Navigation's
// internal Uri.decode() actually expects, and idempotent/correct for '%'
// already present in a URI string), and do NOT decode a second time when
// reading the argument back out -- Navigation has already decoded it once.
private fun String.encode(): String = android.net.Uri.encode(this)
