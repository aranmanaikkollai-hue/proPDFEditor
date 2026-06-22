package com.propdf.nas.data.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.Directory
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.nas.domain.model.NasConfig
import com.propdf.nas.domain.model.RemoteFile
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMB/CIFS client using SMBJ.
 */
@Singleton
class SmbClient @Inject constructor(
    private val dispatcherProvider: DispatcherProvider
) {
    private val client = SMBClient()

    private fun connect(config: NasConfig.SmbConfig): DiskShare {
        val connection = client.connect(config.host, config.port)
        val authContext = AuthenticationContext(
            config.username,
            config.password.toCharArray(),
            config.domain
        )
        val session = connection.authenticate(authContext)
        return session.connectShare(config.shareName) as DiskShare
    }

    suspend fun listFiles(config: NasConfig.SmbConfig, remotePath: String): AppResult<List<RemoteFile>> =
        withContext(dispatcherProvider.io) {
            try {
                val share = connect(config)
                val path = remotePath.removePrefix("/").ifEmpty { "" }

                val files = share.list(path).map { file ->
                    RemoteFile(
                        name = file.fileName,
                        path = "$remotePath/${file.fileName}",
                        isDirectory = file.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                        size = file.endOfFile,
                        lastModified = file.lastWriteTime.toEpochMillis()
                    )
                }.filter { it.name !in listOf(".", "..") }

                AppResult.Success(files)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("SMB list failed: ${e.message}"))
            }
        }

    suspend fun upload(config: NasConfig.SmbConfig, remotePath: String, inputStream: InputStream): AppResult<Unit> =
        withContext(dispatcherProvider.io) {
            try {
                val share = connect(config)
                val path = remotePath.removePrefix("/")

                val file: File = share.openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null
                )

                file.outputStream.use { output ->
                    inputStream.copyTo(output)
                }
                file.close()

                AppResult.Success(Unit)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("SMB upload failed: ${e.message}"))
            }
        }

    suspend fun download(config: NasConfig.SmbConfig, remotePath: String): AppResult<InputStream> =
        withContext(dispatcherProvider.io) {
            try {
                val share = connect(config)
                val path = remotePath.removePrefix("/")

                val file: File = share.openFile(
                    path,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )

                val inputStream = file.inputStream
                AppResult.Success(inputStream)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("SMB download failed: ${e.message}"))
            }
        }

    suspend fun delete(config: NasConfig.SmbConfig, remotePath: String): AppResult<Unit> =
        withContext(dispatcherProvider.io) {
            try {
                val share = connect(config)
                val path = remotePath.removePrefix("/")
                share.rm(path)
                AppResult.Success(Unit)
            } catch (e: Exception) {
                AppResult.Error(AppException.IOError("SMB delete failed: ${e.message}"))
            }
        }

    suspend fun testConnection(config: NasConfig.SmbConfig): AppResult<Boolean> =
        withContext(dispatcherProvider.io) {
            try {
                val share = connect(config)
                share.isConnected
                AppResult.Success(true)
            } catch (e: Exception) {
                AppResult.Success(false)
            }
        }
}
