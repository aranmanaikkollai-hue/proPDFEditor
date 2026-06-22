package com.propdf.nas.data.webdav

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.nas.domain.model.NasConfig
import com.propdf.nas.domain.model.RemoteFile
import com.thegrizzlylabs.sardineandroid.Sardine
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV client using Sardine-Android.
 */
@Singleton
class WebDavClient @Inject constructor(
    private val dispatcherProvider: DispatcherProvider
) {
    private fun createSardine(config: NasConfig.WebDavConfig): Sardine {
        val sardine = OkHttpSardine()
        sardine.setCredentials(config.username, config.password)
        return sardine
    }

    private fun buildUrl(config: NasConfig.WebDavConfig, path: String): String {
        val protocol = if (config.useHttps) "https" else "http"
        val cleanPath = path.removePrefix("/")
        return "$protocol://${config.host}:${config.port}${config.basePath}$cleanPath"
    }

    suspend fun listFiles(config: NasConfig.WebDavConfig, remotePath: String): AppResult<List<RemoteFile>> =
        withContext(dispatcherProvider.io) {
            try {
                val sardine = createSardine(config)
                val url = buildUrl(config, remotePath)
                val resources = sardine.list(url)

                val files = resources.drop(1).map { res -> // Drop first (self)
                    RemoteFile(
                        name = res.name,
                        path = "$remotePath/${res.name}",
                        isDirectory = res.isDirectory,
                        size = res.contentLength,
                        lastModified = res.modified?.time ?: 0
                    )
                }

                AppResult.Success(files)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("WebDAV list failed: ${e.message}"))
            }
        }

    suspend fun upload(config: NasConfig.WebDavConfig, remotePath: String, inputStream: InputStream, contentLength: Long): AppResult<Unit> =
        withContext(dispatcherProvider.io) {
            try {
                val sardine = createSardine(config)
                val url = buildUrl(config, remotePath)
                sardine.put(url, inputStream, contentLength)
                AppResult.Success(Unit)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("WebDAV upload failed: ${e.message}"))
            }
        }

    suspend fun download(config: NasConfig.WebDavConfig, remotePath: String): AppResult<InputStream> =
        withContext(dispatcherProvider.io) {
            try {
                val sardine = createSardine(config)
                val url = buildUrl(config, remotePath)
                val stream = sardine.get(url)
                AppResult.Success(stream)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("WebDAV download failed: ${e.message}"))
            }
        }

    suspend fun delete(config: NasConfig.WebDavConfig, remotePath: String): AppResult<Unit> =
        withContext(dispatcherProvider.io) {
            try {
                val sardine = createSardine(config)
                val url = buildUrl(config, remotePath)
                sardine.delete(url)
                AppResult.Success(Unit)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("WebDAV delete failed: ${e.message}"))
            }
        }

    suspend fun testConnection(config: NasConfig.WebDavConfig): AppResult<Boolean> =
        withContext(dispatcherProvider.io) {
            try {
                val sardine = createSardine(config)
                val url = buildUrl(config, "/")
                sardine.exists(url)
                AppResult.Success(true)
            } catch (e: Exception) {
                AppResult.Success(false)
            }
        }
}
