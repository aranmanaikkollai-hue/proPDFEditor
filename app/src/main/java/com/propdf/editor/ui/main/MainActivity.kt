package com.propdf.editor.ui.main

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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
import com.propdf.editor.ui.cloud.CloudScreen
import com.propdf.editor.ui.favorites.FavoritesScreen
import com.propdf.editor.ui.home.HomeScreen
import com.propdf.editor.ui.recyclebin.RecycleBinScreen
import com.propdf.editor.ui.settings.SettingsScreen
import com.propdf.editor.ui.theme.ProPDFTheme
import com.propdf.viewer.presentation.PremiumViewerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    private val viewModel: MainViewModel by viewModels()

    @Inject
    lateinit var safEngine: SafEngine

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

    /**
     * Observes [MainViewModel.uiState] for a pending viewer URI.
     * When set, resolves the content URI to a cache File via [SafEngine],
     * then launches [PremiumViewerActivity] with the absolute file path.
     */
    private fun observeViewerLaunch() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    val uriStr = state.launchViewerUri ?: return@collect
                    viewModel.onViewerLaunched()  // clear immediately to avoid re-triggering

                    val uri = runCatching { Uri.parse(uriStr) }.getOrNull() ?: return@collect

                    // Resolve content:// URI to a real File the PdfRenderer can open
                    val fileResult = safEngine.resolveToFile(uri)
                    if (fileResult is AppResult.Success) {
                        val intent = PremiumViewerActivity.createIntent(
                            this@MainActivity,
                            fileResult.data.absolutePath
                        )
                        startActivity(intent)
                    }
                    // If resolveToFile fails, the error is swallowed here;
                    // the ViewModel already surfaces errors via errorMessage.
                }
            }
        }
    }
}

@Composable
fun MainApp(viewModel: MainViewModel) {
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
            composable(Screen.Home.route) { HomeScreen(navController, viewModel) }
            composable(Screen.Favorites.route) { FavoritesScreen(navController) }
            composable(Screen.Cloud.route) { CloudScreen(navController) }
            composable(Screen.RecycleBin.route) { RecycleBinScreen(navController) }
            composable(Screen.Settings.route) { SettingsScreen(navController) }
        }
    }
}
