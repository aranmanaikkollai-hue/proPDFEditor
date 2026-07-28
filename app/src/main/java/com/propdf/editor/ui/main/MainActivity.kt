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
import com.propdf.editor.ui.viewer.ViewerActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private companion object {
        val SUPPORTED_OPEN_MIME_TYPES = arrayOf(
            "application/pdf",
            "image/*",
            "text/plain",
            "text/html",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        )
    }

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
                    ProPDFNavigation(
                        mainViewModel = viewModel,
                        onOpenPdf = { pdfPicker.launch(SUPPORTED_OPEN_MIME_TYPES) }
                    )
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collectLatest { state ->
                    state.launchViewerUri?.let { uriString ->
                        ViewerActivity.start(
                            this@MainActivity,
                            Uri.parse(uriString),
                            displayName = null
                        )
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
