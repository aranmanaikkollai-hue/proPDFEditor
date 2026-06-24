package com.propdf.share.data.nearby

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.propdf.share.domain.model.ShareDevice
import com.propdf.share.domain.model.ShareSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nearby Connections API manager for offline P2P sharing.
 * Works without internet — uses Bluetooth + WiFi Direct.
 */
@Singleton
class NearbyShareManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context)

    private val _sessionState = MutableStateFlow<ShareSession>(ShareSession.Idle)
    val sessionState: StateFlow<ShareSession> = _sessionState

    private val _discoveredDevices = MutableStateFlow<List<ShareDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<ShareDevice>> = _discoveredDevices

    private val discoveredEndpoints = mutableMapOf<String, String>() // endpointId -> name

    private val strategy = Strategy.P2P_POINT_TO_POINT

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            discoveredEndpoints[endpointId] = info.endpointName
            _discoveredDevices.value = discoveredEndpoints.map { (id, name) ->
                ShareDevice(id = id, displayName = name, endpointId = id)
            }
        }

        override fun onEndpointLost(endpointId: String) {
            discoveredEndpoints.remove(endpointId)
            _discoveredDevices.value = discoveredEndpoints.map { (id, name) ->
                ShareDevice(id = id, displayName = name, endpointId = id)
            }
        }
    }

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Auto-accept for simplicity — in production, show confirmation dialog
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                val device = ShareDevice(
                    id = endpointId,
                    displayName = discoveredEndpoints[endpointId] ?: "Unknown",
                    endpointId = endpointId
                )
                _sessionState.value = ShareSession.Connected(device)
            }
        }

        override fun onDisconnected(endpointId: String) {
            _sessionState.value = ShareSession.Idle
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Handle received file
            if (payload.type == Payload.Type.FILE) {
                _sessionState.value = ShareSession.Transferring(0.5f, "Receiving...")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.IN_PROGRESS -> {
                    val progress = update.bytesTransferred.toFloat() / update.totalBytes
                    _sessionState.value = ShareSession.Transferring(progress, "Transferring...")
                }
                PayloadTransferUpdate.Status.SUCCESS -> {
                    _sessionState.value = ShareSession.Completed(android.net.Uri.EMPTY)
                }
                PayloadTransferUpdate.Status.FAILURE -> {
                    _sessionState.value = ShareSession.Error("Transfer failed")
                }
            }
        }
    }

    suspend fun startAdvertising(displayName: String) {
        val options = AdvertisingOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startAdvertising(
            displayName,
            "com.propdf.editor.share",
            connectionLifecycleCallback,
            options
        ).await()
        _sessionState.value = ShareSession.Advertising
    }

    suspend fun startDiscovery() {
        val options = DiscoveryOptions.Builder().setStrategy(strategy).build()
        connectionsClient.startDiscovery(
            "com.propdf.editor.share",
            endpointDiscoveryCallback,
            options
        ).await()
        _sessionState.value = ShareSession.Discovering
    }

    suspend fun connectToDevice(endpointId: String) {
        connectionsClient.requestConnection(
            "ProPDF User",
            endpointId,
            connectionLifecycleCallback
        ).await()
    }

    suspend fun sendFile(endpointId: String, fileUri: android.net.Uri) {
        val pfd = context.contentResolver.openFileDescriptor(fileUri, "r")
            ?: throw IllegalArgumentException("Cannot open file: $fileUri")
        val payload = com.google.android.gms.nearby.connection.Payload.fromFile(pfd)
        connectionsClient.sendPayload(endpointId, payload).await()
    }

    fun stopAll() {
        connectionsClient.stopAdvertising()
        connectionsClient.stopDiscovery()
        connectionsClient.stopAllEndpoints()
        _sessionState.value = ShareSession.Idle
        _discoveredDevices.value = emptyList()
        discoveredEndpoints.clear()
    }
}
