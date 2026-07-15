package com.propdf.editor.data.converter

import android.content.Context
import android.net.Uri
import com.propdf.editor.domain.model.ConversionResult
import com.propdf.editor.utils.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.BufferedInputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class ZipExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val BUFFER_SIZE = 8192
        private const val COMPRESSION_LEVEL = 6
    }
    
    suspend fun createZip(
        context: Context,
        sourceUris: List<Uri>,
        outputDir: File,
        fileName: String,
        onProgress: suspend (Int) -> Unit
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            if (sourceUris.isEmpty()) {
                return@withContext ConversionResult(false, null, fileName, "No files selected")
            }
            
            val outputFile = File(outputDir, "$fileName.zip")
            val totalFiles = sourceUris.size
            
            ZipArchiveOutputStream(outputFile.outputStream()).use { zos ->
                zos.setLevel(COMPRESSION_LEVEL)
                zos.setMethod(ZipArchiveOutputStream.DEFLATED)
                
                sourceUris.forEachIndexed { index, uri ->
                    if (!coroutineContext.isActive) {
                        zos.close()
                        outputFile.delete()
                        return@withContext ConversionResult(false, null, fileName, "Cancelled")
                    }
                    
                    val fileName = FileUtils.getFileName(context, uri) ?: "file_$index"
                    val entry = ZipArchiveEntry(fileName)
                    
                    // Get file size if available
                    val fileSize = FileUtils.getFileSize(context, uri)
                    if (fileSize > 0) {
                        entry.size = fileSize
                    }
                    
                    zos.putArchiveEntry(entry)
                    
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        BufferedInputStream(input, BUFFER_SIZE).use { bis ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int
                            while (bis.read(buffer).also { bytesRead = it } != -1) {
                                zos.write(buffer, 0, bytesRead)
                            }
                        }
                    }
                    
                    zos.closeArchiveEntry()
                    onProgress(((index + 1) * 100) / totalFiles)
                }
            }
            
            val outputUri = FileUtils.getUriForFile(context, outputFile)
            ConversionResult(
                true,
                outputUri,
                fileName,
                "Created ZIP with $totalFiles files",
                fileCount = totalFiles,
                totalBytes = outputFile.length()
            )
            
        } catch (e: Exception) {
            ConversionResult(false, null, fileName, e.message ?: "ZIP export failed")
        }
    }
}
