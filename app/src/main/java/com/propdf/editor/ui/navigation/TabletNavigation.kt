package com.propdf.editor.ui.navigation

import androidx.compose.animation.*
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.propdf.editor.ui.home.HomeScreen
import com.propdf.editor.ui.main.MainViewModel
import com.propdf.editor.ui.settings.SettingsScreen
import com.propdf.editor.ui.viewer.PdfViewerScreen

@Composable
fun TabletNavigation(
    mainViewModel: MainViewModel,
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
                        onClick = { /* Open PDF picker */ },
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
                    mainViewModel = mainViewModel
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
                    mainViewModel = mainViewModel
                )
            }
        }
    }
}

@Composable
fun ProPDFNavHost(
    navController: NavHostController,
    mainViewModel: MainViewModel
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
                onOpenPdf = { /* Open PDF picker */ }
            )
        }
        composable(Screen.Files.route) {
            // Files screen
        }
        composable(Screen.Scanner.route) {
            // Scanner screen
        }
        composable(Screen.Tools.route) {
            // Tools screen
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
