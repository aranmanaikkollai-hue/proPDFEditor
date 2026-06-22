package com.propdf.share.domain.model

import android.net.Uri

/**
 * Represents a device available for offline sharing.
 */
data class ShareDevice(
    val id: String,
    val displayName: String,
    val endpointId: String, // Nearby Connections endpoint
    val signalStrength: Int = 0
)

/**
 * Share session state.
 */
sealed class ShareSession {
    object Idle : ShareSession()
    object Advertising : ShareSession()
    object Discovering : ShareSession()
    data class Connected(val device: ShareDevice) : ShareSession()
    data class Transferring(val progress: Float, val fileName: String) : ShareSession()
    data class Completed(val fileUri: Uri) : ShareSession()
    data class Error(val message: String) : ShareSession()
}

/**
 * LAN share server info for QR generation.
 */
data class LanServerInfo(
    val ipAddress: String,
    val port: Int,
    val fileToken: String
)
