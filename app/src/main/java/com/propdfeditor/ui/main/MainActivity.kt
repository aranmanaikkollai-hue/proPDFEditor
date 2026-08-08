package com.propdfeditor.ui.main

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.propdfeditor.ui.navigation.AppNavigation
import com.propdfeditor.ui.theme.ProPDFTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var isReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !isReady }

        enableEdgeToEdge()

        setContent {
            ProPDFTheme {
                val navController = rememberNavController()
                // uiState currently only gates the splash screen (see isReady below);
                // AppNavigation does not take a uiState param.
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                AppNavigation(
                    navController = navController
                )
            }
        }

        // Mark ready immediately — do not block splash on optional services
        isReady = true
    }
}
