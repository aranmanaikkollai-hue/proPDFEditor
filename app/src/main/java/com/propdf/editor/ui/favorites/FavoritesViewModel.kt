package com.propdf.editor.ui.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.usecase.GetFavoritesUseCase
import com.propdf.editor.domain.usecase.ToggleFavoriteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val getFavorites: GetFavoritesUseCase,
    private val toggleFavorite: ToggleFavoriteUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            getFavorites().collect { files ->
                _uiState.update { it.copy(
                    files = files,
                    isLoading = false,
                    isEmpty = files.isEmpty()
                ) }
            }
        }
    }

    fun removeFavorite(id: Long) {
        viewModelScope.launch {
            toggleFavorite(id, true)
        }
    }
}

data class FavoritesUiState(
    val files: List<PdfDocument> = emptyList(),
    val isLoading: Boolean = true,
    val isEmpty: Boolean = false,
    val error: String? = null
)
