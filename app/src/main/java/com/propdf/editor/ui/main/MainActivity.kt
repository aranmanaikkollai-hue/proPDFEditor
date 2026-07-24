package com.propdf.editor.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.propdf.editor.ui.navigation.ProPDFNavigation
import com.propdf.editor.ui.navigation.TabletNavigation
import com.propdf.editor.ui.theme.ProPDFTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    val viewModel: MainViewModel by viewModels()
    private var isReady by mutableStateOf(false)

    val pdfPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { }
        viewModel.openPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !isReady }

        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { viewModel.openPdf(it) }
        }

        setContent {
            val darkTheme by viewModel.isDarkMode.collectAsStateWithLifecycle()
            val dynamicColors by viewModel.useDynamicColors.collectAsStateWithLifecycle()

            val isDark = darkTheme ?: isSystemInDarkTheme()

            ProPDFTheme(
                darkTheme = isDark,
                dynamicColor = dynamicColors
            ) {
                val isTablet = resources.configuration.screenWidthDp >= 600
                if (isTablet) {
                    TabletNavigation(mainViewModel = viewModel)
                } else {
                    ProPDFNavigation(mainViewModel = viewModel)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    if (state.launchViewerUri != null) {
                        viewModel.onViewerLaunched()
                    }
                    isReady = true
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { viewModel.openPdf(it) }
        }
    }
}
