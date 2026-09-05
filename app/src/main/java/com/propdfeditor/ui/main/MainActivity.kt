package com.propdfeditor.ui.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.propdfeditor.ui.navigation.AppNavigation
import com.propdfeditor.ui.theme.ProPDFTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private val viewModel: MainViewModel by viewModels()
    private var isReady = false

    /**
     * Holds an external PDF URI received via Intent.ACTION_VIEW or ACTION_SEND.
     * Passed into AppNavigation to trigger a one-time navigation to the viewer.
     */
    private var pendingExternalUri by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !isReady }

        enableEdgeToEdge()

        // Process the intent that started this activity (cold-start external PDF).
        handleIntent(intent)

        setContent {
            val storedDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
            val isDynamicColor by viewModel.isDynamicColor.collectAsStateWithLifecycle()
            val isDarkMode = storedDarkMode ?: isSystemInDarkTheme()

            ProPDFTheme(darkTheme = isDarkMode, dynamicColor = isDynamicColor) {
                val navController = rememberNavController()
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()

                AppNavigation(
                    navController = navController,
                    pendingExternalUri = pendingExternalUri,
                    onExternalUriConsumed = { pendingExternalUri = null }
                )
            }
        }

        // Mark ready immediately — do not block splash on optional services
        isReady = true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    /**
     * Inspects the incoming intent for external PDF data.
     * Supports ACTION_VIEW (open) and ACTION_SEND (share).
     *
     * For content:// URIs, attempts to retain read permission for the
     * current session. Does NOT request broad storage permissions.
     */
    private fun handleIntent(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri: Uri? = intent.data
                val mimeType: String? = intent.type

                if (uri != null && isPdfMimeType(mimeType, uri)) {
                    grantReadPermission(intent, uri)
                    pendingExternalUri = uri.toString()
                    Log.i(TAG, "Received ACTION_VIEW PDF: $uri")
                }
            }

            Intent.ACTION_SEND -> {
                val uri: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)
                if (uri != null && isPdfMimeType(intent.type, uri)) {
                    grantReadPermission(intent, uri)
                    pendingExternalUri = uri.toString()
                    Log.i(TAG, "Received ACTION_SEND PDF: $uri")
                }
            }
        }
    }

    /**
     * Grants read URI permission for content:// URIs when possible.
     * Catches SecurityException for providers that do not support
     * persistable permissions.
     */
    private fun grantReadPermission(intent: Intent, uri: Uri) {
        if (uri.scheme == "content") {
            try {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Some providers (e.g., Downloads) do not support
                // persistable permissions. The FLAG_GRANT_READ_URI_PERMISSION
                // on the intent is usually sufficient for the current session.
                Log.w(TAG, "Cannot take persistable URI permission for $uri", e)
            }
        }
    }

    /**
     * Determines whether the given URI represents a PDF.
     * Checks MIME type first, then falls back to path/extension inspection.
     */
    private fun isPdfMimeType(mimeType: String?, uri: Uri): Boolean {
        if (mimeType == "application/pdf") return true
        val path = uri.lastPathSegment ?: uri.toString()
        return path.endsWith(".pdf", ignoreCase = true)
    }
}
