package com.propdf.editor.ui.conversion

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.propdf.editor.data.local.ConversionTaskDao
import com.propdf.editor.data.repository.ConversionRepository
import com.propdf.editor.domain.model.*
import com.propdf.editor.worker.ConversionWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class ConversionViewModel @Inject constructor(
    application: Application,
    private val repository: ConversionRepository,
    private val taskDao: ConversionTaskDao,
    private val workManager: WorkManager
) : AndroidViewModel(application) {

    private val _conversionState = MutableStateFlow<ConversionUiState>(ConversionUiState.Idle)
    val conversionState: StateFlow<ConversionUiState> = _conversionState.asStateFlow()

    private val _selectedFiles = MutableStateFlow<List<Uri>>(emptyList())
    val selectedFiles: StateFlow<List<Uri>> = _selectedFiles.asStateFlow()

    private val _currentTask = MutableStateFlow<ConversionTask?>(null)
    val currentTask: StateFlow<ConversionTask?> = _currentTask.asStateFlow()

    val allTasks: Flow<List<ConversionTask>> = taskDao.getAllTasks()
    val recentTasks: Flow<List<ConversionTask>> = taskDao.getRecentTasksByType(ConversionType.PDF_TO_IMAGES)

    fun selectFiles(uris: List<Uri>) {
        _selectedFiles.value = uris
    }

    fun addFile(uri: Uri) {
        _selectedFiles.value = _selectedFiles.value + uri
    }

    fun removeFile(uri: Uri) {
        _selectedFiles.value = _selectedFiles.value - uri
    }

    fun clearSelection() {
        _selectedFiles.value = emptyList()
    }

    fun startConversion(
        type: ConversionType,
        outputFileName: String
    ) {
        val files = _selectedFiles.value
        if (files.isEmpty() && type != ConversionType.ZIP_EXPORT) {
            _conversionState.value = ConversionUiState.Error("No files selected")
            return
        }

        viewModelScope.launch {
            try {
                _conversionState.value = ConversionUiState.Preparing

                val taskId = repository.createTask(type, files, outputFileName)
                val task = taskDao.getTaskById(taskId)
                _currentTask.value = task

                // Enqueue work
                val inputData = workDataOf(
                    ConversionWorker.KEY_TASK_ID to taskId,
                    ConversionWorker.KEY_CONVERSION_TYPE to type.name,
                    ConversionWorker.KEY_OUTPUT_NAME to outputFileName,
                    ConversionWorker.KEY_SOURCE_URIS to files.map { it.toString() }.toTypedArray()
                )

                val constraints = Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<ConversionWorker>()
                    .setInputData(inputData)
                    .setConstraints(constraints)
                    .setBackoffCriteria(
                        BackoffPolicy.EXPONENTIAL,
                        WorkRequest.MIN_BACKOFF_MILLIS,
                        TimeUnit.MILLISECONDS
                    )
                    .addTag(ConversionWorker.WORK_TAG)
                    .addTag("${ConversionWorker.WORK_TAG}_$taskId")
                    .build()

                workManager.enqueueUniqueWork(
                    "conversion_$taskId",
                    ExistingWorkPolicy.KEEP,
                    workRequest
                )

                // Observe work progress
                observeWorkProgress(workRequest.id, taskId)

                _conversionState.value = ConversionUiState.Processing(taskId)

            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.message ?: "Failed to start conversion")
            }
        }
    }

    private fun observeWorkProgress(workId: UUID, taskId: String) {
        workManager.getWorkInfoByIdLiveData(workId).observeForever { workInfo ->
            when (workInfo?.state) {
                WorkInfo.State.RUNNING -> {
                    val progress = workInfo.progress.getInt("progress", 0)
                    _conversionState.value = ConversionUiState.Progress(taskId, progress)
                }
                WorkInfo.State.SUCCEEDED -> {
                    val success = workInfo.outputData.getBoolean(ConversionWorker.KEY_RESULT_SUCCESS, false)
                    val uri = workInfo.outputData.getString(ConversionWorker.KEY_RESULT_URI)
                    val message = workInfo.outputData.getString(ConversionWorker.KEY_RESULT_MESSAGE) ?: ""
                    val count = workInfo.outputData.getInt(ConversionWorker.KEY_RESULT_FILE_COUNT, 1)

                    _conversionState.value = ConversionUiState.Success(
                        taskId,
                        uri?.let { Uri.parse(it) },
                        message,
                        count
                    )
                    _selectedFiles.value = emptyList()
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                    val message = workInfo.outputData.getString("error")
                        ?: workInfo.outputData.getString(ConversionWorker.KEY_RESULT_MESSAGE)
                        ?: "Conversion failed"
                    _conversionState.value = ConversionUiState.Error(message)
                }
                else -> {}
            }
        }
    }

    fun cancelConversion(taskId: String) {
        viewModelScope.launch {
            repository.cancelTask(taskId)
            workManager.cancelAllWorkByTag("${ConversionWorker.WORK_TAG}_$taskId")
            _conversionState.value = ConversionUiState.Cancelled
        }
    }

    fun deleteTask(taskId: String) {
        viewModelScope.launch {
            repository.deleteTask(taskId)
            workManager.cancelAllWorkByTag("${ConversionWorker.WORK_TAG}_$taskId")
        }
    }

    fun retryTask(taskId: String) {
        viewModelScope.launch {
            val task = taskDao.getTaskById(taskId) ?: return@launch
            startConversion(task.conversionType, task.outputFileName)
        }
    }

    fun cleanup() {
        viewModelScope.launch {
            repository.cleanupOldTasks(30)
        }
    }

    fun resetState() {
        _conversionState.value = ConversionUiState.Idle
        _currentTask.value = null
    }
}

sealed class ConversionUiState {
    object Idle : ConversionUiState()
    object Preparing : ConversionUiState()
    data class Processing(val taskId: String) : ConversionUiState()
    data class Progress(val taskId: String, val percent: Int) : ConversionUiState()
    data class Success(
        val taskId: String,
        val outputUri: Uri?,
        val message: String,
        val fileCount: Int
    ) : ConversionUiState()
    data class Error(val message: String) : ConversionUiState()
    object Cancelled : ConversionUiState()
}
