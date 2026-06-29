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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.propdf.editor.ui.files.FilesScreen
import com.propdf.editor.ui.home.HomeScreen
import com.propdf.editor.ui.recent.RecentFilesScreen
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
                        if (uri != null) {
                            contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            // Records to recent files AND fires OpenPdf event
                            viewModel.openPdf(uri)
                        }
                    }

                    // Handle ViewModel navigation events
                    LaunchedEffect(Unit) {
                        viewModel.events.collect { event ->
                            when (event) {
                                is MainViewModel.Event.OpenPdf -> {
                                    navController.navigate(
                                        "viewer/${Uri.encode(event.uri.toString())}"
                                    )
                                }
                                is MainViewModel.Event.OpenScanner -> {
                                    startActivity(
                                        Intent(this@MainActivity, DocumentScannerActivity::class.java)
                                    )
                                }
                                is MainViewModel.Event.OpenTools -> {
                                    navController.navigate("tools")
                                }
                                is MainViewModel.Event.Error -> Unit
                            }
                        }
                    }

                    val bottomNavItems = listOf(
                        BottomNavItem("home", "Home", Icons.Default.Home),
                        BottomNavItem("files", "Files", Icons.Default.Folder),
                        BottomNavItem("recent", "Recent", Icons.Default.Description),
                        BottomNavItem("tools", "Tools", Icons.Default.Build),
                        BottomNavItem("settings", "Settings", Icons.Default.Settings)
                    )

                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route
                    val showBottomBar = bottomNavItems.any { it.route == currentRoute }

                    Scaffold(
                        bottomBar = {
                            if (showBottomBar) {
                                NavigationBar {
                                    bottomNavItems.forEach { item ->
                                        NavigationBarItem(
                                            icon = { Icon(item.icon, item.label) },
                                            label = { Text(item.label) },
                                            selected = navBackStackEntry?.destination
                                                ?.hierarchy?.any { it.route == item.route } == true,
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
                    ) { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = "home",
                            modifier = Modifier.padding(innerPadding)
                        ) {
                            composable("home") {
                                HomeScreen(
                                    navController = navController,
                                    mainViewModel = viewModel,
                                    onOpenPdf = {
                                        pdfPickerLauncher.launch(arrayOf("application/pdf"))
                                    }
                                )
                            }
                            composable("files") {
                                FilesScreen(
                                    viewModel = viewModel,
                                    onOpenPdf = {
                                        pdfPickerLauncher.launch(arrayOf("application/pdf"))
                                    }
                                )
                            }
                            composable("recent") {
                                RecentFilesScreen(navController = navController)
                            }
                            composable("tools") {
                                ToolsScreen(navController = navController)
                            }
                            composable("settings") {
                                SettingsScreen(navController = navController)
                            }
                            composable("viewer/{uri}") { backStackEntry ->
                                val encodedUri = backStackEntry.arguments?.getString("uri") ?: ""
                                val decodedUri = Uri.decode(encodedUri)
                                PdfViewerScreen(
                                    uri = decodedUri,
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable("scanner") {
                                LaunchedEffect(Unit) {
                                    navController.popBackStack()
                                    startActivity(
                                        Intent(this@MainActivity, DocumentScannerActivity::class.java)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
