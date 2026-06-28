package com.propdf.security.biometric

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Enterprise biometric authentication manager.
 */
class BiometricAuthManager(private val context: Context) {

    private val biometricManager: BiometricManager by lazy {
        BiometricManager.from(context)
    }

    enum class BiometricStatus {
        AVAILABLE,
        NOT_ENROLLED,
        NOT_AVAILABLE,
        UNSUPPORTED,
        UNKNOWN
    }

    fun checkStatus(): BiometricStatus {
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricStatus.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricStatus.NOT_AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricStatus.NOT_AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricStatus.NOT_ENROLLED
            else -> BiometricStatus.UNKNOWN
        }
    }

    fun hasBiometricHardware(): Boolean {
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) !=
                BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
    }

    fun isBiometricReady(): Boolean = checkStatus() == BiometricStatus.AVAILABLE

    suspend fun authenticate(
        activity: FragmentActivity,
        title: String = "Authenticate",
        subtitle: String = "Verify your identity to continue",
        negativeButtonText: String? = "Cancel"
    ): AppResult<Unit> = withContext(Dispatchers.Main) {
        when (val status = checkStatus()) {
            BiometricStatus.NOT_AVAILABLE ->
                return@withContext AppResult.Error(AppException.BiometricError("Biometric hardware not available"))
            BiometricStatus.NOT_ENROLLED ->
                return@withContext AppResult.Error(AppException.BiometricError("No biometric credentials enrolled"))
            BiometricStatus.UNSUPPORTED ->
                return@withContext AppResult.Error(AppException.BiometricError("Biometric authentication not supported"))
            else -> { /* proceed */ }
        }

        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)

            val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)

            if (negativeButtonText != null) {
                promptInfoBuilder.setNegativeButtonText(negativeButtonText)
            } else {
                promptInfoBuilder.setDeviceCredentialAllowed(true)
            }

            val promptInfo = promptInfoBuilder.build()

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) {
                        continuation.resume(AppResult.Success(Unit))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) {
                        val error = when (errorCode) {
                            BiometricPrompt.ERROR_USER_CANCELED,
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON ->
                                AppException.BiometricError("Authentication cancelled")
                            BiometricPrompt.ERROR_LOCKOUT,
                            BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                                AppException.BiometricError("Too many failed attempts. Try again later.")
                            BiometricPrompt.ERROR_NO_BIOMETRICS ->
                                AppException.BiometricError("No biometric credentials enrolled")
                            BiometricPrompt.ERROR_HW_NOT_PRESENT,
                            BiometricPrompt.ERROR_HW_UNAVAILABLE ->
                                AppException.BiometricError("Biometric hardware unavailable")
                            else -> AppException.BiometricError(errString.toString())
                        }
                        continuation.resume(AppResult.Error(exception = error))
                    }
                }

                override fun onAuthenticationFailed() {
                    // Don't resume here - wait for error or success
                }
            }

            val biometricPrompt = BiometricPrompt(activity, executor, callback)
            biometricPrompt.authenticate(promptInfo)

            continuation.invokeOnCancellation {
                biometricPrompt.cancelAuthentication()
            }
        }
    }

    suspend fun authenticateWithCredentialFallback(
        activity: FragmentActivity,
        title: String = "Authenticate",
        subtitle: String = "Verify your identity"
    ): AppResult<Unit> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) {
                        continuation.resume(AppResult.Success(Unit))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) {
                        continuation.resume(AppResult.Error(exception =
                            AppException.BiometricError(errString.toString())
                        ))
                    }
                }

                override fun onAuthenticationFailed() {
                    // Wait for error or success
                }
            }

            val biometricPrompt = BiometricPrompt(activity, executor, callback)
            biometricPrompt.authenticate(promptInfo)

            continuation.invokeOnCancellation {
                biometricPrompt.cancelAuthentication()
            }
        }
    }
}
