package com.propdf.editor.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.propdf.editor.ui.cloud.CloudScreen
import com.propdf.editor.ui.favorites.FavoritesScreen
import com.propdf.editor.ui.home.HomeScreen
import com.propdf.editor.ui.recyclebin.RecycleBinScreen
import com.propdf.editor.ui.settings.SettingsScreen
import com.propdf.editor.ui.theme.ProPDFTheme
import dagger.hilt.android.AndroidEntryPoint

sealed class Screen(val route: String, val titleRes: Int, val iconRes: Int) {
    object Home : Screen("home", com.propdf.editor.R.string.nav_home, com.propdf.editor.R.drawable.ic_home)
    object Favorites : Screen("favorites", com.propdf.editor.R.string.nav_favorites, com.propdf.editor.R.drawable.ic_favorite)
    object Cloud : Screen("cloud", com.propdf.editor.R.string.nav_cloud, com.propdf.editor.R.drawable.ic_cloud)
    object RecycleBin : Screen("recyclebin", com.propdf.editor.R.string.nav_recycle, com.propdf.editor.R.drawable.ic_delete)
    object Settings : Screen("settings", com.propdf.editor.R.string.nav_settings, com.propdf.editor.R.drawable.ic_settings)
}

val bottomNavItems = listOf(Screen.Home, Screen.Favorites, Screen.Cloud, Screen.RecycleBin, Screen.Settings)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProPDFTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainApp()
                }
            }
        }
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()
    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            NavigationBar {
                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = { Icon(painter = painterResource(id = screen.iconRes), contentDescription = stringResource(id = screen.titleRes)) },
                        label = { Text(stringResource(id = screen.titleRes)) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(300)) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(300)) },
            popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(300)) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(300)) }
        ) {
            composable(Screen.Home.route) { HomeScreen(navController) }
            composable(Screen.Favorites.route) { FavoritesScreen(navController) }
            composable(Screen.Cloud.route) { CloudScreen(navController) }
            composable(Screen.RecycleBin.route) { RecycleBinScreen(navController) }
            composable(Screen.Settings.route) { SettingsScreen(navController) }
        }
    }
}
