package com.propdf.editor.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.propdf.core.domain.model.PdfDocument
import com.propdf.editor.navigation.DocumentManagerDestination
import com.propdf.editor.ui.files.*
import com.propdf.editor.ui.home.HomeScreen
import com.propdf.editor.ui.scanner.DocumentScannerActivity
import com.propdf.editor.ui.settings.SettingsScreen
import com.propdf.editor.ui.theme.ProPDFTheme
import com.propdf.editor.ui.tools.ToolsScreen
import com.propdf.editor.ui.viewer.PdfViewerScreen
import dagger.hilt.android.AndroidEntryPoint

data class BottomNavItem(val route: String, val label: String, val icon: ImageVector)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ProPDFTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    val pdfPickerLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri: Uri? ->
                        uri?.let { viewModel.handlePickedDocument(it) }
                    }

                    MainContent(
                        navController = navController,
                        pdfPickerLauncher = pdfPickerLauncher,
                        onOpenDocument = { document ->
                            navController.navigate("viewer/${Uri.encode(document.uriString)}")
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MainContent(
    navController: NavHostController,
    pdfPickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
    onOpenDocument: (PdfDocument) -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    
    // Don't show bottom nav on certain screens
    val hideBottomNavRoutes = listOf(
        "viewer/",
        "storage_analyzer",
        "duplicate_finder",
        "recent_activity",
        "folder_browser"
    )
    val showBottomNav = hideBottomNavRoutes.none { 
        currentDestination?.route?.startsWith(it) == true 
    }

    Scaffold(
        bottomBar = {
            if (showBottomNav) {
                NavigationBar {
                    val items = listOf(
                        BottomNavItem("home", "Home", Icons.Default.Home),
                        BottomNavItem("files", "Files", Icons.Default.Folder),
                        BottomNavItem("tools", "Tools", Icons.Default.Build),
                        BottomNavItem("settings", "Settings", Icons.Default.Settings)
                    )
                    
                    items.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding)
        ) {
            composable("home") {
                HomeScreen(
                    navController = navController,
                    onOpenDocument = onOpenDocument,
                    onNavigateToFiles = { navController.navigate("files") },
                    onNavigateToRecent = { navController.navigate("recent") },
                    onNavigateToFavorites = { navController.navigate("favorites") },
                    onNavigateToTools = { navController.navigate("tools") }
                )
            }
            
            composable("files") {
                DocumentManagerScreen(
                    navController = navController,
                    onOpenDocument = { docId -> navController.navigate("viewer/$docId") },
                    onNavigateToViewer = { },
                    onNavigateToMerge = { /* TODO */ },
                    onNavigateToSplit = { /* TODO */ },
                    onNavigateToFolder = { }
                )
            }
            
            composable("recent") {
                DocumentManagerScreen(
                    navController = navController,
                    onOpenDocument = { docId -> navController.navigate("viewer/$docId") },
                    onNavigateToViewer = { },
                    onNavigateToMerge = { /* TODO */ },
                    onNavigateToSplit = { /* TODO */ },
                    onNavigateToFolder = { }
                )
            }
            
            composable("favorites") {
                DocumentManagerScreen(
                    navController = navController,
                    onOpenDocument = { docId -> navController.navigate("viewer/$docId") },
                    onNavigateToViewer = { },
                    onNavigateToMerge = { /* TODO */ },
                    onNavigateToSplit = { /* TODO */ },
                    onNavigateToFolder = { }
                )
            }
            
            composable("tools") {
                ToolsScreen(
                    onOpenScanner = {
                        navController.context.startActivity(
                            Intent(navController.context, DocumentScannerActivity::class.java)
                        )
                    },
                    onOpenStorageAnalyzer = {
                        navController.navigate(DocumentManagerDestination.StorageAnalyzer.route)
                    },
                    onOpenDuplicateFinder = {
                        navController.navigate(DocumentManagerDestination.DuplicateFinder.route)
                    },
                    onOpenRecentActivity = {
                        navController.navigate(DocumentManagerDestination.RecentActivity.route)
                    },
                    openLegacyTools = { /* TODO */ }
                )
            }
            
            composable("settings") {
                SettingsScreen()
            }
            
            composable(DocumentManagerDestination.StorageAnalyzer.route) {
                StorageAnalyzerScreen(navController = navController)
            }
            
            composable(DocumentManagerDestination.DuplicateFinder.route) {
                DuplicateFinderScreen(navController = navController)
            }
            
            composable(DocumentManagerDestination.RecentActivity.route) {
                RecentActivityScreen(navController = navController)
            }
            
            composable(
                route = DocumentManagerDestination.FolderBrowser.route,
                arguments = listOf(navArgument("path") { type = NavType.StringType })
            ) { backStackEntry ->
                val path = backStackEntry.arguments?.getString("path") ?: "/"
                FolderBrowserScreen(
                    navController = navController,
                    folderId = path
                )
            }
            
            composable(
                route = "viewer/{documentId}",
                arguments = listOf(navArgument("documentId") { type = NavType.StringType })
            ) { backStackEntry ->
                val documentId = backStackEntry.arguments?.getString("documentId") ?: ""
                PdfViewerScreen(
                    uri = documentId,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
