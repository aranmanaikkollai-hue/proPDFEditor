package com.propdfeditor.compression.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.data.local.CompressionHistoryDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CompressionHistoryViewModel @Inject constructor(
    private val historyDao: CompressionHistoryDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            
            historyDao.getRecent(100).collect { history ->
                _uiState.update {
                    it.copy(
                        history = history,
                        isLoading = false
                    )
                }
            }
        }
        
        viewModelScope.launch {
            historyDao.getTotalSpaceSaved().collect { saved ->
                _uiState.update { it.copy(totalSpaceSaved = saved ?: 0) }
            }
        }
    }
}

data class HistoryUiState(
    val history: List<com.propdf.core.data.local.CompressionHistoryEntity> = emptyList(),
    val isLoading: Boolean = false,
    val totalSpaceSaved: Long = 0
)
