package com.propdf.security.ui

import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.propdf.core.domain.model.SessionState
import com.propdf.core.domain.result.AppResult
import com.propdf.security.biometric.BiometricAuthManager
import com.propdf.security.session.SessionManager
import kotlinx.coroutines.launch

/**
 * Biometric guard composable that overlays content when session is locked.
 *
 * Usage:
 * ```
 * BiometricGuard(
 *     sessionManager = sessionManager,
 *     biometricAuthManager = biometricAuthManager,
 *     onUnlock = { /* optional callback */ }
 * ) {
 *     // Your protected content here
 * }
 * ```
 */
@Composable
fun BiometricGuard(
    sessionManager: SessionManager,
    biometricAuthManager: BiometricAuthManager,
    onUnlock: () -> Unit = {},
    content: @Composable () -> Unit
) {
    val sessionState by sessionManager.sessionState.collectAsState()
    var authError by remember { mutableStateOf<String?>(null) }

    when (sessionState) {
        is SessionState.Unlocked -> {
            content()
        }
        is SessionState.Expiring -> {
            // Show content with warning overlay
            Box(modifier = Modifier.fillMaxSize()) {
                content()
                SessionExpiringOverlay(
                    secondsRemaining = (sessionState as SessionState.Expiring).secondsRemaining,
                    onExtend = { sessionManager.onUserActivity() }
                )
            }
        }
        is SessionState.Locked -> {
            LockedScreen(
                biometricAuthManager = biometricAuthManager,
                onAuthenticated = {
                    sessionManager.unlockSession()
                    onUnlock()
                },
                errorMessage = authError,
                onErrorDismiss = { authError = null }
            )
        }
    }
}

@Composable
private fun LockedScreen(
    biometricAuthManager: BiometricAuthManager,
    onAuthenticated: () -> Unit,
    errorMessage: String?,
    onErrorDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = "Locked",
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Session Locked",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Authenticate to continue working with your documents",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = {
                    activity?.let { fragmentActivity ->
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            when (val result = biometricAuthManager.authenticate(fragmentActivity)) {
                                is AppResult.Success -> onAuthenticated()
                                is AppResult.Error -> onErrorDismiss()
                                else -> {}
                            }
                        }
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text("Unlock with Biometric")
            }

            errorMessage?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SessionExpiringOverlay(
    secondsRemaining: Int,
    onExtend: () -> Unit
) {
    // Implementation of warning banner
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        // Warning UI
    }
}
