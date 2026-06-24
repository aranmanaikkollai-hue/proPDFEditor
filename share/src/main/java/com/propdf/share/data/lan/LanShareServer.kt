package com.propdf.share.data.lan

import android.content.Context
import android.net.Uri
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.share.domain.model.LanServerInfo
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight HTTP server for LAN sharing using NanoHTTPD.
 * Serves PDFs over local WiFi without internet.
 */
@Singleton
class LanShareServer @Inject constructor(
    private val context: Context,
    private val dispatcherProvider: DispatcherProvider
) {
    private var server: NanoHTTPD? = null
    private val scope = CoroutineScope(SupervisorJob() + dispatcherProvider.io)

    private val sharedFiles = mutableMapOf<String, Uri>() // token -> file URI

    fun start(port: Int = 8080): LanServerInfo? {
        if (server?.isAlive == true) return getServerInfo(port)

        return try {
            server = object : NanoHTTPD(port) {
                override fun serve(session: IHTTPSession): Response {
                    val uri = session.uri
                    val token = uri.removePrefix("/").substringBefore("/")

                    return when {
                        uri == "/health" -> newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                        sharedFiles.containsKey(token) -> serveFile(token)
                        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
                    }
                }
            }
            server?.start()
            getServerInfo(port)
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        server?.stop()
        server = null
        sharedFiles.clear()
        scope.cancel()
    }

    fun shareFile(uri: Uri): String {
        val token = java.util.UUID.randomUUID().toString().substring(0, 8)
        sharedFiles[token] = uri
        return token
    }

    fun removeFile(token: String) {
        sharedFiles.remove(token)
    }

    fun getServerInfo(port: Int): LanServerInfo? {
        val ip = getLocalIpAddress() ?: return null
        return LanServerInfo(
            ipAddress = ip,
            port = port,
            fileToken = sharedFiles.keys.firstOrNull() ?: ""
        )
    }

    private fun serveFile(token: String): NanoHTTPD.Response {
        val uri = sharedFiles[token] ?: return NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.NOT_FOUND,
            "text/plain",
            "File not found"
        )

        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.NOT_FOUND, "text/plain", "Cannot open file")

            val fis = FileInputStream(pfd.fileDescriptor)
            val size = pfd.statSize

            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/pdf", fis, size)
        } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.hostAddress?.startsWith("192.168.") == true }
                ?.hostAddress
        } catch (e: Exception) {
            null
        }
    }

    fun isRunning(): Boolean = server?.isAlive == true
}
