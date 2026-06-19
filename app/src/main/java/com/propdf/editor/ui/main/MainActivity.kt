package com.propdf.editor.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideIntoContainer
import androidx.compose.animation.slideOutOfContainer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.propdf.core.domain.result.AppResult
import com.propdf.core.saf.SafEngine
import com.propdf.editor.data.local.SettingsDataStore
import com.propdf.editor.ui.files.FilesScreen
import com.propdf.editor.ui.home.HomeScreen
import com.propdf.editor.ui.scanner.ModernScannerActivity
import com.propdf.editor.ui.settings.SettingsScreen
import com.propdf.editor.ui.theme.ProPDFThemeWithSettings
import com.propdf.editor.ui.tools.LaunchCardScreen
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.ui.tools.ToolsScreen
import com.propdf.viewer.presentation.PremiumViewerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Default.Home)
    data object Files : Screen("files", "Files", Icons.Default.Description)
    data object Scanner : Screen("scanner", "Scanner", Icons.Default.PhotoCamera)
    data object Tools : Screen("tools", "Tools", Icons.Default.Build)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

val bottomNavItems = listOf(Screen.Home, Screen.Files, Screen.Scanner, Screen.Tools, Screen.Settings)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var safEngine: SafEngine
    @Inject lateinit var settingsDataStore: SettingsDataStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is MainViewModel.Event.OpenPdf -> launchViewer(event.uri)
                        is MainViewModel.Event.OpenScanner -> launchScanner()
                        is MainViewModel.Event.OpenTools -> launchTools()
                        is MainViewModel.Event.Error -> {
                            // Show snackbar or toast
                        }
                    }
                }
            }
        }

        setContent {
            // Use DataStore-aware theme for persistent settings
            ProPDFThemeWithSettings(settingsDataStore = settingsDataStore) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }

    private fun launchViewer(uri: Uri) {
        val intent = Intent(this, PremiumViewerActivity::class.java).apply {
            data = uri
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(intent)
    }

    private fun launchScanner() {
        startActivity(Intent(this, ModernScannerActivity::class.java))
    }

    private fun launchTools() {
        startActivity(Intent(this, ToolsActivity::class.java))
    }
}

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.openPdf(it) }
    }

    val onOpenPdf = remember { { launcher.launch(arrayOf("application/pdf")) } }

    Scaffold(
        bottomBar = {
            if (!isWideScreen) {
                BottomNavBar(navController)
            }
        }
    ) { padding ->
        Row(modifier = Modifier.fillMaxSize()) {
            if (isWideScreen) {
                SideNavRail(navController)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Screen.Home.route,
                    modifier = Modifier.fillMaxSize()
                ) {
                    composable(Screen.Home.route) {
                        HomeScreen(
                            navController = navController,
                            mainViewModel = viewModel,
                            onOpenPdf = onOpenPdf
                        )
                    }
                    composable(Screen.Files.route) {
                        FilesScreen(viewModel = viewModel, onOpenPdf = onOpenPdf)
                    }
                    composable(Screen.Scanner.route) {
                        // Scanner is handled by bottom nav click
                        HomeScreen(
                            navController = navController,
                            mainViewModel = viewModel,
                            onOpenPdf = onOpenPdf
                        )
                    }
                    composable(Screen.Tools.route) {
                        ToolsScreen(navController)
                    }
                    composable(Screen.Settings.route,
                        enterTransition = {
                            slideIntoContainer(
                                AnimatedContentTransitionScope.SlideDirection.Left,
                                animationSpec = tween(300)
                            )
                        },
                        exitTransition = {
                            slideOutOfContainer(
                                AnimatedContentTransitionScope.SlideDirection.Right,
                                animationSpec = tween(300)
                            )
                        }
                    ) {
                        SettingsScreen(navController)
                    }
                }
            }
        }
    }
}

@Composable
fun BottomNavBar(navController: androidx.navigation.NavController) {
    NavigationBar {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        bottomNavItems.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = screen.title) },
                label = { Text(screen.title) },
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    if (screen.route == "scanner") {
                        // Launch scanner activity
                        return@NavigationBarItem
                    }
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                modifier = Modifier.semantics {
                    contentDescription = "${screen.title} tab"
                }
            )
        }
    }
}

@Composable
fun SideNavRail(navController: androidx.navigation.NavController) {
    NavigationRail {
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination
        bottomNavItems.forEach { screen ->
            NavigationRailItem(
                icon = { Icon(screen.icon, contentDescription = screen.title) },
                label = { Text(screen.title) },
                selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                onClick = {
                    if (screen.route == "scanner") {
                        return@NavigationRailItem
                    }
                    navController.navigate(screen.route) {
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

