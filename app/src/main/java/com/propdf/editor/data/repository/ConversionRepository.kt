package com.propdf.editor.data.repository

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.propdf.editor.data.converter.*
import com.propdf.editor.data.local.ConversionTaskDao
import com.propdf.editor.domain.model.*
import com.propdf.editor.utils.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskDao: ConversionTaskDao,
    private val pdfConverter: PdfConverter,
    private val imageConverter: ImageConverter,
    private val textConverter: TextConverter,
    private val htmlConverter: HtmlConverter,
    private val markdownConverter: MarkdownConverter,
    private val zipExporter: ZipExporter
) {
    fun getAllTasks(): Flow<List<ConversionTask>> = taskDao.getAllTasks()
    
    fun getTasksByStatus(status: ConversionStatus): Flow<List<ConversionTask>> = 
        taskDao.getTasksByStatus(status)
    
    suspend fun getTaskById(taskId: String): ConversionTask? = taskDao.getTaskById(taskId)
    
    suspend fun createTask(
        type: ConversionType,
        sourceUris: List<Uri>,
        outputFileName: String
    ): String = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()
        val task = ConversionTask(
            id = taskId,
            conversionType = type,
            sourceUris = sourceUris.map { it.toString() },
            outputUri = null,
            status = ConversionStatus.PENDING,
            progress = 0,
            errorMessage = null,
            createdAt = Date(),
            completedAt = null,
            outputFileName = outputFileName
        )
        taskDao.insertTask(task)
        taskId
    }
    
    suspend fun executeTask(taskId: String): ConversionResult = withContext(Dispatchers.IO) {
        val task = taskDao.getTaskById(taskId) ?: return@withContext ConversionResult(
            false, null, "", "Task not found"
        )
        
        try {
            taskDao.updateTaskProgress(taskId, ConversionStatus.RUNNING, 0, null)
            
            val sourceUris = task.sourceUris.map { it.toUri() }
            val outputDir = FileUtils.getConversionOutputDir(context)
            
            val result = when (task.conversionType) {
                ConversionType.PDF_TO_IMAGES -> pdfConverter.toImages(
                    context, sourceUris.first(), outputDir, task.outputFileName
                ) { progress -> 
                    updateProgress(taskId, progress) 
                }
                
                ConversionType.IMAGES_TO_PDF -> imageConverter.toPdf(
                    context, sourceUris, outputDir, task.outputFileName
                ) { progress -> 
                    updateProgress(taskId, progress) 
                }
                
                ConversionType.PDF_TO_TXT -> pdfConverter.toText(
                    context, sourceUris.first(), outputDir, task.outputFileName
                ) { progress -> 
                    updateProgress(taskId, progress) 
                }
                
                ConversionType.TXT_TO_PDF -> textConverter.toPdf(
                    context, sourceUris.first(), outputDir, task.outputFileName
                ) { progress -> 
                    updateProgress(taskId, progress) 
                }
                
                ConversionType.HTML_TO_PDF -> htmlConverter.toPdf(
                    context, sourceUris.first(), outputDir, task.outputFileName
                ) { progress -> 
                    updateProgress(taskId, progress) 
                }
                
                ConversionType.MARKDOWN_TO_PDF -> markdownConverter.toPdf(
                    context, sourceUris.first(), outputDir, task.outputFileName
                ) { progress -> 
                    updateProgress(taskId, progress) 
                }
                
                ConversionType.MERGE_IMAGES -> imageConverter.mergeImages(
                    context, sourceUris, outputDir, task.outputFileName
                ) { progress -> 
                    updateProgress(taskId, progress) 
                }
                
                ConversionType.SPLIT_IMAGE -> imageConverter.splitImage(
                    context, sourceUris.first(), outputDir, task.outputFileName
                ) { progress -> 
                    updateProgress(taskId, progress) 
                }
                
                ConversionType.ZIP_EXPORT -> zipExporter.createZip(
                    context, sourceUris, outputDir, task.outputFileName
                ) { progress -> 
                    updateProgress(taskId, progress) 
                }
            }
            
            if (result.success) {
                taskDao.completeTask(
                    taskId, 
                    ConversionStatus.SUCCESS, 
                    System.currentTimeMillis(),
                    result.outputUri?.toString()
                )
            } else {
                taskDao.updateTaskProgress(
                    taskId, 
                    ConversionStatus.FAILED, 
                    100, 
                    result.message
                )
            }
            
            result
            
        } catch (e: Exception) {
            taskDao.updateTaskProgress(
                taskId, 
                ConversionStatus.FAILED, 
                100, 
                e.message ?: "Unknown error"
            )
            ConversionResult(false, null, task.outputFileName, e.message ?: "Unknown error")
        }
    }
    
    private suspend fun updateProgress(taskId: String, progress: Int) {
        taskDao.updateTaskProgress(taskId, ConversionStatus.RUNNING, progress, null)
    }
    
    suspend fun cancelTask(taskId: String) {
        taskDao.updateTaskProgress(taskId, ConversionStatus.CANCELLED, 100, "Cancelled by user")
    }
    
    suspend fun deleteTask(taskId: String) {
        val task = taskDao.getTaskById(taskId)
        task?.outputUri?.let { uri ->
            try {
                context.contentResolver.delete(uri.toUri(), null, null)
            } catch (e: Exception) {
                // Ignore deletion errors
            }
        }
        taskDao.deleteTask(taskId)
    }
    
    suspend fun cleanupOldTasks(daysToKeep: Int = 30) {
        val cutoff = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)
        taskDao.deleteOldTasks(ConversionStatus.SUCCESS, cutoff)
        taskDao.deleteOldTasks(ConversionStatus.FAILED, cutoff)
    }
}
