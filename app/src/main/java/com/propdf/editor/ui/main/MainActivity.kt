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
import com.propdf.editor.ui.files.FilesScreen
import com.propdf.editor.ui.home.HomeScreen
import com.propdf.editor.ui.scanner.ModernScannerActivity
import com.propdf.editor.ui.settings.SettingsScreen
import com.propdf.editor.ui.theme.ProPDFTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        observeViewerLaunch()
        setContent {
            ProPDFTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainApp(viewModel)
                }
            }
        }
    }

    private fun observeViewerLaunch() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val uriStr = state.launchViewerUri ?: return@collect
                    viewModel.onViewerLaunched()
                    val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@collect
                    val fileResult = safEngine.resolveToFile(uri)
                    if (fileResult is AppResult.Success) {
                        startActivity(PremiumViewerActivity.createIntent(this@MainActivity, fileResult.data.absolutePath))
                    }
                }
            }
        }
    }
}

@Composable
fun MainApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val useRail = configuration.screenWidthDp >= 600 || configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val openPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.openPdf(it)
        }
    }
    val openPdf = remember(openPdfLauncher) { { openPdfLauncher.launch(arrayOf("application/pdf")) } }

    AdaptiveNavigationScaffold(navController = navController, useRail = useRail) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding),
            enterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(220)) },
            exitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, animationSpec = tween(220)) },
            popEnterTransition = { slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(220)) },
            popExitTransition = { slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, animationSpec = tween(220)) }
        ) {
            composable(Screen.Home.route) { HomeScreen(navController, viewModel, onOpenPdf = openPdf) }
            composable(Screen.Files.route) { FilesScreen(viewModel, onOpenPdf = openPdf) }
            composable(Screen.Scanner.route) { LaunchCardScreen("Scanner", "Capture documents offline with the built-in scanner.", "Open Scanner") { context.startActivity(Intent(context, ModernScannerActivity::class.java)) } }
            composable(Screen.Tools.route) { ToolsScreen { context.startActivity(Intent(context, ToolsActivity::class.java)) } }
            composable(Screen.Settings.route) { SettingsScreen(navController) }
        }
    }
}

@Composable
private fun AdaptiveNavigationScaffold(navController: androidx.navigation.NavHostController, useRail: Boolean, content: @Composable (PaddingValues) -> Unit) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val navigate: (Screen) -> Unit = { screen ->
        navController.navigate(screen.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    if (useRail) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail { bottomNavItems.forEach { item -> NavItem(item, currentDestination?.hierarchy?.any { it.route == item.route } == true, true) { navigate(item) } } }
            Box(Modifier.weight(1f)) { content(PaddingValues(0.dp)) }
        }
    } else {
        Scaffold(bottomBar = { NavigationBar { bottomNavItems.forEach { item -> NavItem(item, currentDestination?.hierarchy?.any { it.route == item.route } == true, false) { navigate(item) } } } }, content = content)
    }
}

@Composable
private fun NavItem(screen: Screen, selected: Boolean, rail: Boolean, onClick: () -> Unit) {
    if (rail) NavigationRailItem(selected = selected, onClick = onClick, icon = { Icon(screen.icon, screen.title) }, label = { Text(screen.title) })
    else NavigationBarItem(selected = selected, onClick = onClick, icon = { Icon(screen.icon, screen.title) }, label = { Text(screen.title) })
}
