package com.propdf.editor.data.hash

import android.content.Context
import android.net.Uri
import androidx.core.net.toFile
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileHashEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) {
    private val bufferSize = 32 * 1024

    suspend fun computeFastHash(uri: Uri, fileSize: Long): String = withContext(dispatchers.io) {
        val buffer = ByteArray(bufferSize)
        val inputStream = openStream(uri)
            ?: throw IllegalArgumentException("Cannot open URI: $uri")

        inputStream.use { stream ->
            var remaining = 8 * 1024
            val contentBytes = mutableListOf<Byte>()
            while (remaining > 0) {
                val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                if (read == -1) break
                contentBytes.addAll(buffer.take(read).toList())
                remaining -= read
            }
            FastHash.hashWithSizePrefix(contentBytes.toByteArray(), fileSize)
        }
    }

    suspend fun computeStrongHash(uri: Uri, fileSize: Long): String? = withContext(dispatchers.io) {
        if (fileSize > 100 * 1024 * 1024) {
            logger.w("FileHashEngine", "Skipping strong hash for large file: $fileSize bytes")
            return@withContext null
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        val inputStream = openStream(uri) ?: return@withContext null

        DigestInputStream(inputStream, digest).use { dis ->
            while (dis.read(buffer) != -1) { /* consume */ }
        }

        digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun computeBothHashes(uri: Uri, fileSize: Long): Pair<String, String?> =
        withContext(dispatchers.io) {
            val strongDigest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(bufferSize)
            val contentBytes = mutableListOf<Byte>()

            val inputStream = openStream(uri)
                ?: throw IllegalArgumentException("Cannot open URI: $uri")

            inputStream.use { stream ->
                var totalRead = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read == -1) break
                    strongDigest.update(buffer, 0, read)
                    if (totalRead < 8 * 1024) {
                        val fastBytes = minOf(read, 8 * 1024 - totalRead)
                        contentBytes.addAll(buffer.take(fastBytes).toList())
                    }
                    totalRead += read
                }
            }

            val fastHash = FastHash.hashWithSizePrefix(contentBytes.toByteArray(), fileSize)
            val strongHash = if (fileSize <= 100 * 1024 * 1024) {
                strongDigest.digest().joinToString("") { "%02x".format(it) }
            } else null

            Pair(fastHash, strongHash)
        }

    private fun openStream(uri: Uri) = when (uri.scheme) {
        "file" -> FileInputStream(uri.toFile())
        "content" -> context.contentResolver.openInputStream(uri)
        else -> null
    }
}
