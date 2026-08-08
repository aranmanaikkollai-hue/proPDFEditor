package com.propdf.editor.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.propdf.editor.ui.files.FilesScreen
import com.propdf.editor.ui.home.HomeScreen
import com.propdf.editor.ui.ocr.OcrActivity
import com.propdf.editor.ui.scanner.ModernScannerActivity
import com.propdf.editor.ui.search.SearchScreen
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.ui.tools.ToolsScreen
import com.propdf.editor.ui.main.MainViewModel
import com.propdf.editor.ui.settings.SettingsScreen
import com.propdf.editor.ui.viewer.PdfViewerScreen

@Composable
fun TabletNavigation(
    mainViewModel: MainViewModel,
    onOpenPdf: () -> Unit,
    navController: NavHostController = rememberNavController()
) {
    val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val navItems = listOf(
        Screen.Home,
        Screen.Files,
        Screen.Scanner,
        Screen.Tools,
        Screen.Settings
    )

    if (isLandscape) {
        // Landscape: Navigation Rail on the left
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surface,
                header = {
                    FloatingActionButton(
                        onClick = onOpenPdf,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(vertical = 16.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add PDF")
                    }
                },
                modifier = Modifier.fillMaxHeight()
            ) {
                Spacer(modifier = Modifier.weight(1f))
                navItems.forEach { screen ->
                    val selected = currentRoute == screen.route
                    NavigationRailItem(
                        icon = {
                            Icon(
                                if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationRailItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            }
            Box(modifier = Modifier.weight(1f)) {
                ProPDFNavHost(
                    navController = navController,
                    mainViewModel = mainViewModel,
                    onOpenPdf = onOpenPdf
                )
            }
        }
    } else {
        // Portrait: Bottom Navigation
        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    navItems.forEach { screen ->
                        val selected = currentRoute == screen.route
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    if (selected) screen.selectedIcon else screen.unselectedIcon,
                                    contentDescription = screen.title
                                )
                            },
                            label = { Text(screen.title) },
                            selected = selected,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                ProPDFNavHost(
                    navController = navController,
                    mainViewModel = mainViewModel,
                    onOpenPdf = onOpenPdf
                )
            }
        }
    }
}

@Composable
fun ProPDFNavHost(
    navController: NavHostController,
    mainViewModel: MainViewModel,
    onOpenPdf: () -> Unit
) {
    androidx.navigation.compose.NavHost(
        navController = navController,
        startDestination = Screen.Home.route,
        enterTransition = {
            fadeIn(animationSpec = tween(300)) +
            slideInHorizontally(initialOffsetX = { it / 4 }, animationSpec = tween(300))
        },
        exitTransition = {
            fadeOut(animationSpec = tween(300)) +
            slideOutHorizontally(targetOffsetX = { -it / 4 }, animationSpec = tween(300))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) +
            slideInHorizontally(initialOffsetX = { -it / 4 }, animationSpec = tween(300))
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(300)) +
            slideOutHorizontally(targetOffsetX = { it / 4 }, animationSpec = tween(300))
        }
    ) {
        composable(Screen.Home.route) {
            HomeScreen(
                navController = navController,
                mainViewModel = mainViewModel,
                onOpenPdf = onOpenPdf
            )
        }
        composable(Screen.Files.route) {
            FilesScreen(navController = navController, mainViewModel = mainViewModel)
        }
        composable(Screen.Scanner.route) {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                context.startActivity(android.content.Intent(context, ModernScannerActivity::class.java))
                navController.popBackStack()
            }
        }
        composable(Screen.Tools.route) {
            ToolsScreen(navController = navController)
        }
        composable("search") {
            SearchScreen(navController = navController, mainViewModel = mainViewModel)
        }
        composable("recent") {
            FilesScreen(navController = navController, mainViewModel = mainViewModel)
        }
        composable("folders") {
            com.propdf.editor.ui.files.FolderBrowserScreen(navController = navController)
        }
        composable("folder_browser/{folderId}") { backStackEntry ->
            com.propdf.editor.ui.files.FolderBrowserScreen(
                navController = navController,
                folderId = backStackEntry.arguments?.getString("folderId")
            )
        }
        composable("duplicates") {
            com.propdf.editor.ui.files.DuplicateFinderScreen(navController = navController)
        }
        composable("recent_activity") {
            com.propdf.editor.ui.files.RecentActivityScreen(navController = navController)
        }
        composable("storage_analyzer") {
            com.propdf.editor.ui.files.StorageAnalyzerScreen(navController = navController)
        }
        composable("ocr") {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                context.startActivity(android.content.Intent(context, OcrActivity::class.java))
                navController.popBackStack()
            }
        }
        composable("toolsActivity") {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                context.startActivity(android.content.Intent(context, ToolsActivity::class.java))
                navController.popBackStack()
            }
        }
        composable(Screen.Settings.route) {
            SettingsScreen(navController = navController)
        }
        composable("viewer/{fileName}") { backStackEntry ->
            PdfViewerScreen(
                navController = navController,
                fileName = backStackEntry.arguments?.getString("fileName") ?: "Document.pdf"
            )
        }
    }
}
