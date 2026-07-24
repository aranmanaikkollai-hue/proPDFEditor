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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.propdf.editor.ui.home.HomeScreen
import com.propdf.editor.ui.main.MainViewModel
import com.propdf.editor.ui.settings.SettingsScreen
import com.propdf.editor.ui.viewer.PdfViewerScreen

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    object Home : Screen("home", "Home", Icons.Filled.Home, Icons.Outlined.Home)
    object Files : Screen("files", "Files", Icons.Filled.Folder, Icons.Outlined.Folder)
    object Scanner : Screen("scanner", "Scanner", Icons.Filled.DocumentScanner, Icons.Outlined.DocumentScanner)
    object Tools : Screen("tools", "Tools", Icons.Filled.Build, Icons.Outlined.Build)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

@Composable
fun ProPDFNavigation(
    mainViewModel: MainViewModel,
    navController: NavHostController = rememberNavController()
) {
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val bottomNavItems = listOf(Screen.Home, Screen.Files, Screen.Scanner, Screen.Tools)

    Scaffold(
        bottomBar = {
            if (!isTablet && currentRoute in bottomNavItems.map { it.route }) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    bottomNavItems.forEach { screen ->
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
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
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
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(padding),
            enterTransition = {
                slideInHorizontally(
                    initialOffsetX = { it / 4 },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { -it / 4 },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                slideInHorizontally(
                    initialOffsetX = { -it / 4 },
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutHorizontally(
                    targetOffsetX = { it / 4 },
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    navController = navController,
                    mainViewModel = mainViewModel,
                    onOpenPdf = { /* Open PDF picker */ }
                )
            }
            composable(Screen.Files.route) {
                // Files screen implementation
            }
            composable(Screen.Scanner.route) {
                // Scanner screen implementation
            }
            composable(Screen.Tools.route) {
                // Tools screen implementation
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
}
