package com.propdfeditor.batch.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.propdfeditor.batch.data.entity.BatchJobEntity
import com.propdfeditor.batch.data.util.BatchJobStatus
import com.propdfeditor.batch.data.util.BatchJobType
import com.propdfeditor.batch.repository.BatchJobRepository
import com.propdfeditor.batch.scheduler.BatchWorkScheduler
import com.propdfeditor.batch.util.BatchNotificationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class BatchViewModel @Inject constructor(
    application: Application,
    private val repository: BatchJobRepository,
    private val scheduler: BatchWorkScheduler,
    private val notificationManager: BatchNotificationManager
) : AndroidViewModel(application) {

    private val _jobs = MutableStateFlow<List<BatchJobEntity>>(emptyList())
    val jobs: StateFlow<List<BatchJobEntity>> = _jobs.asStateFlow()

    private val _activeJobs = MutableStateFlow<List<BatchJobEntity>>(emptyList())
    val activeJobs: StateFlow<List<BatchJobEntity>> = _activeJobs.asStateFlow()

    private val _selectedUris = MutableStateFlow<List<Uri>>(emptyList())
    val selectedUris: StateFlow<List<Uri>> = _selectedUris.asStateFlow()

    private val _currentOperation = MutableStateFlow<BatchJobType?>(null)
    val currentOperation: StateFlow<BatchJobType?> = _currentOperation.asStateFlow()

    private val _operationResult = MutableLiveData<OperationResult>()
    val operationResult: LiveData<OperationResult> = _operationResult

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    init {
        viewModelScope.launch {
            repository.allJobs.collectLatest { _jobs.value = it }
        }
        viewModelScope.launch {
            repository.activeJobs.collectLatest { _activeJobs.value = it }
        }
    }

    fun selectUris(uris: List<Uri>) {
        _selectedUris.value = uris
    }

    fun addUri(uri: Uri) {
        val current = _selectedUris.value.toMutableList()
        if (!current.contains(uri)) {
            current.add(uri)
            _selectedUris.value = current
        }
    }

    fun removeUri(uri: Uri) {
        _selectedUris.value = _selectedUris.value.filter { it != uri }
    }

    fun clearSelection() {
        _selectedUris.value = emptyList()
    }

    fun setOperation(type: BatchJobType) {
        _currentOperation.value = type
    }

    fun startBatchOperation(
        type: BatchJobType,
        configJson: String = "{}",
        outputUri: Uri? = null,
        outputDirUri: Uri? = null
    ) {
        val inputUris = _selectedUris.value
        if (inputUris.isEmpty()) {
            _operationResult.value = OperationResult.Error("No files selected")
            return
        }

        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val finalConfig = if (outputDirUri != null) {
                    // Inject output directory into config
                    val configMap = com.google.gson.Gson().fromJson(configJson, Map::class.java).toMutableMap()
                    configMap["outputDirUri"] = outputDirUri.toString()
                    com.google.gson.Gson().toJson(configMap)
                } else {
                    configJson
                }

                val jobId = repository.createJob(
                    type = type,
                    inputUris = inputUris,
                    outputUri = outputUri,
                    configJson = finalConfig
                )

                val job = repository.getJobById(jobId) ?: throw IllegalStateException("Job not found")
                val workId = scheduler.scheduleBatchJob(job)

                observeWorkProgress(workId, jobId)

                _operationResult.value = OperationResult.Success("Batch operation started", jobId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start batch operation")
                _operationResult.value = OperationResult.Error(e.message ?: "Unknown error")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun observeWorkProgress(workId: UUID, jobId: Long) {
        scheduler.observeJob(workId).observeForever { workInfo ->
            when (workInfo?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    viewModelScope.launch {
                        val job = repository.getJobById(jobId)
                        job?.let {
                            notificationManager.showCompletionNotification(it, true)
                        }
                    }
                }
                WorkInfo.State.FAILED -> {
                    viewModelScope.launch {
                        val job = repository.getJobById(jobId)
                        job?.let {
                            val error = workInfo.outputData.getString("error") ?: "Unknown error"
                            notificationManager.showCompletionNotification(it, false, error)
                        }
                    }
                }
                WorkInfo.State.CANCELLED -> {
                    viewModelScope.launch {
                        repository.updateStatus(jobId, BatchJobStatus.CANCELLED)
                    }
                }
                else -> { /* RUNNING, ENQUEUED, BLOCKED */ }
            }
        }
    }

    fun cancelJob(jobId: Long) {
        viewModelScope.launch {
            scheduler.cancelJob(jobId)
            repository.cancelJob(jobId)
            notificationManager.cancelNotification(jobId)
        }
    }

    fun deleteJob(job: BatchJobEntity) {
        viewModelScope.launch {
            if (job.status == BatchJobStatus.RUNNING) {
                scheduler.cancelJob(job.id)
            }
            notificationManager.cancelNotification(job.id)
            repository.deleteJob(job)
        }
    }

    fun deleteOldCompletedJobs() {
        viewModelScope.launch {
            repository.deleteOldCompletedJobs()
        }
    }

    fun retryFailedJob(job: BatchJobEntity) {
        if (job.status != BatchJobStatus.FAILED) return

        viewModelScope.launch {
            repository.updateStatus(job.id, BatchJobStatus.PENDING)
            val updatedJob = repository.getJobById(job.id) ?: return@launch
            val workId = scheduler.scheduleBatchJob(updatedJob)
            observeWorkProgress(workId, job.id)
        }
    }

    fun clearResult() {
        _operationResult.value = null
    }

    sealed class OperationResult {
        data class Success(val message: String, val jobId: Long) : OperationResult()
        data class Error(val message: String) : OperationResult()
    }
}
