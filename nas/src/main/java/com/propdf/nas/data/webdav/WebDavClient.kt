package com.propdf.nas.data.webdav

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.nas.domain.model.NasConfig
import com.propdf.nas.domain.model.RemoteFile
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebDAV client using OkHttp.
 * Replaces sardine-android (JitPack-only) with a pure OkHttp implementation.
 */
@Singleton
class WebDavClient @Inject constructor(
    private val dispatcherProvider: DispatcherProvider
) {
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private fun buildUrl(config: NasConfig.WebDavConfig, path: String): String {
        val protocol = if (config.useHttps) "https" else "http"
        val cleanPath = path.removePrefix("/")
        return "$protocol://${config.host}:${config.port}${config.basePath}$cleanPath"
    }

    private fun authHeader(config: NasConfig.WebDavConfig): String =
        Credentials.basic(config.username, config.password)

    suspend fun listFiles(
        config: NasConfig.WebDavConfig,
        remotePath: String
    ): AppResult<List<RemoteFile>> = withContext(dispatcherProvider.io) {
        try {
            val url = buildUrl(config, remotePath)
            val propfindBody =
                """<?xml version="1.0" encoding="utf-8"?>
<propfind xmlns="DAV:"><prop>
<resourcetype/><getcontentlength/><getlastmodified/><displayname/>
</prop></propfind>""".trimIndent()
                .toRequestBody("application/xml; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url(url)
                .method("PROPFIND", propfindBody)
                .header("Authorization", authHeader(config))
                .header("Depth", "1")
                .build()

            val response: Response = httpClient.newCall(request).execute()
            if (!response.isSuccessful && response.code != 207) {
                return@withContext AppResult.Error(
                    AppException.IOError("WebDAV PROPFIND failed: ${response.code}")
                )
            }
            val bodyString = response.body?.string() ?: ""
            val files = parsePropfindResponse(bodyString, remotePath)
            AppResult.Success(files)
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("WebDAV list failed: ${e.message}"))
        }
    }

    suspend fun upload(
        config: NasConfig.WebDavConfig,
        remotePath: String,
        inputStream: InputStream,
        contentLength: Long
    ): AppResult<Unit> = withContext(dispatcherProvider.io) {
        try {
            val url = buildUrl(config, remotePath)
            val bytes = inputStream.readBytes()
            val body: RequestBody = bytes.toRequestBody(
                "application/octet-stream".toMediaTypeOrNull()
            )
            val request = Request.Builder()
                .url(url)
                .put(body)
                .header("Authorization", authHeader(config))
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 201 || response.code == 204) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(AppException.IOError("WebDAV upload failed: ${response.code}"))
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("WebDAV upload failed: ${e.message}"))
        }
    }

    suspend fun download(
        config: NasConfig.WebDavConfig,
        remotePath: String
    ): AppResult<InputStream> = withContext(dispatcherProvider.io) {
        try {
            val url = buildUrl(config, remotePath)
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", authHeader(config))
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val stream = response.body?.byteStream()
                    ?: return@withContext AppResult.Error(
                        AppException.IOError("WebDAV download: empty body")
                    )
                AppResult.Success(stream)
            } else {
                AppResult.Error(AppException.IOError("WebDAV download failed: ${response.code}"))
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("WebDAV download failed: ${e.message}"))
        }
    }

    suspend fun delete(
        config: NasConfig.WebDavConfig,
        remotePath: String
    ): AppResult<Unit> = withContext(dispatcherProvider.io) {
        try {
            val url = buildUrl(config, remotePath)
            val request = Request.Builder()
                .url(url)
                .delete()
                .header("Authorization", authHeader(config))
                .build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 204) {
                AppResult.Success(Unit)
            } else {
                AppResult.Error(AppException.IOError("WebDAV delete failed: ${response.code}"))
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("WebDAV delete failed: ${e.message}"))
        }
    }

    suspend fun testConnection(
        config: NasConfig.WebDavConfig
    ): AppResult<Boolean> = withContext(dispatcherProvider.io) {
        try {
            val url = buildUrl(config, "/")
            val request = Request.Builder()
                .url(url)
                .head()
                .header("Authorization", authHeader(config))
                .build()
            httpClient.newCall(request).execute()
            AppResult.Success(true)
        } catch (e: Exception) {
            AppResult.Success(false)
        }
    }

    // -------------------------------------------------------------------------
    // XML parsing for PROPFIND multi-status response
    // -------------------------------------------------------------------------
    private fun parsePropfindResponse(xml: String, basePath: String): List<RemoteFile> {
        val files = mutableListOf<RemoteFile>()
        var isFirst = true
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(xml.reader())

            var name = ""
            var href = ""
            var isDirectory = false
            var size = 0L
            var lastModified = 0L
            var inResponse = false

            val httpDateFmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                val tag = parser.name ?: ""
                when {
                    event == XmlPullParser.START_TAG && tag.equals("response", true) -> {
                        inResponse = true
                        name = ""; href = ""; isDirectory = false; size = 0L; lastModified = 0L
                    }
                    event == XmlPullParser.END_TAG && tag.equals("response", true) -> {
                        if (inResponse) {
                            if (isFirst) { isFirst = false }
                            else {
                                val fileName = href.trimEnd('/').substringAfterLast('/')
                                    .ifBlank { name }
                                files.add(
                                    RemoteFile(
                                        name = fileName,
                                        path = "$basePath/$fileName",
                                        isDirectory = isDirectory,
                                        size = size,
                                        lastModified = lastModified
                                    )
                                )
                            }
                        }
                        inResponse = false
                    }
                    event == XmlPullParser.START_TAG && tag.equals("href", true) -> {
                        href = parser.nextText()
                    }
                    event == XmlPullParser.START_TAG && tag.equals("displayname", true) -> {
                        name = parser.nextText()
                    }
                    event == XmlPullParser.START_TAG && tag.equals("collection", true) -> {
                        isDirectory = true
                    }
                    event == XmlPullParser.START_TAG && tag.equals("getcontentlength", true) -> {
                        size = parser.nextText().toLongOrNull() ?: 0L
                    }
                    event == XmlPullParser.START_TAG && tag.equals("getlastmodified", true) -> {
                        val dateStr = parser.nextText()
                        lastModified = try {
                            httpDateFmt.parse(dateStr)?.time ?: 0L
                        } catch (e: Exception) { 0L }
                    }
                }
                event = parser.next()
            }
        } catch (_: Exception) {}
        return files
    }
}
