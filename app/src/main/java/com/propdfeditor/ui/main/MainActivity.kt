package com.propdfeditor.ui.main

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.propdfeditor.review.InAppReviewManager
import com.propdfeditor.ui.navigation.AppNavigation
import com.propdfeditor.ui.theme.ProPDFTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var isReady = false

    @Inject
    lateinit var reviewManager: InAppReviewManager

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !isReady }

        enableEdgeToEdge()

        setContent {
            ProPDFTheme(dynamicColor = true) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                    isReady = uiState !is MainUiState.Loading

                    when (val state = uiState) {
                        is MainUiState.Loading -> { /* Splash handles this */ }
                        is MainUiState.Ready -> {
                            ProPDFApp(
                                hasPendingDeepLink = state.hasPendingDeepLink,
                                onFirstReady = { maybeRequestReview() }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun maybeRequestReview() {
        val scope = rememberCoroutineScope()
        scope.launch {
            if (reviewManager.shouldShowReview()) {
                reviewManager.requestReview(this@MainActivity) {
                    // Review flow complete
                }
            }
        }
    }
}

@Composable
fun ProPDFApp(
    hasPendingDeepLink: Boolean,
    onFirstReady: () -> Unit = {}
) {
    val navController = rememberNavController()

    LaunchedEffect(Unit) {
        onFirstReady()
    }

    AppNavigation(
        navController = navController,
        startDestination = if (hasPendingDeepLink) "viewer_route" else "home",
    )
}
