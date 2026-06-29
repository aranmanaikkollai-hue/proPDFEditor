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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.propdf.editor.ui.files.FilesScreen
import com.propdf.editor.ui.home.HomeScreen
import com.propdf.editor.ui.recent.RecentFilesScreen
import com.propdf.editor.ui.settings.SettingsScreen
import com.propdf.editor.ui.theme.ProPDFTheme
import com.propdf.editor.ui.tools.ToolsScreen
import dagger.hilt.android.AndroidEntryPoint

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
                        contract = ActivityResultContracts.OpenDocument()
                    ) { uri: Uri? ->
                        if (uri != null) {
                            contentResolver.takePersistableUriPermission(
                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            viewModel.openPdf(uri)
                        }
                    }

                    // Handle navigation events from ViewModel
                    LaunchedEffect(Unit) {
                        viewModel.events.collect { event ->
                            when (event) {
                                is MainViewModel.Event.OpenPdf -> {
                                    // Navigate to viewer with the URI
                                    navController.navigate("viewer/${Uri.encode(event.uri.toString())}")
                                }
                                is MainViewModel.Event.OpenScanner -> {
                                    navController.navigate("scanner")
                                }
                                is MainViewModel.Event.OpenTools -> {
                                    navController.navigate("tools")
                                }
                                is MainViewModel.Event.Error -> {
                                    // Error is reflected in uiState; no extra navigation needed
                                }
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable("home") {
                            HomeScreen(
                                navController = navController,
                                mainViewModel = viewModel,
                                onOpenPdf = { pdfPickerLauncher.launch(arrayOf("application/pdf")) }
                            )
                        }
                        composable("files") {
                            FilesScreen(
                                viewModel = viewModel,
                                onOpenPdf = { pdfPickerLauncher.launch(arrayOf("application/pdf")) }
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
                    }
                }
            }
        }
    }
}
