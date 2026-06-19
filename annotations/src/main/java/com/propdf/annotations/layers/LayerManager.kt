package com.propdf.annotations.layers

import com.propdf.annotations.model.Annotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Manages multiple annotation layers with z-order, visibility, and grouping.
 */
class LayerManager {

    private val _layers = MutableStateFlow<List<AnnotationLayer>>(emptyList())
    val layers: StateFlow<List<AnnotationLayer>> = _layers.asStateFlow()

    private val _activeLayerId = MutableStateFlow<String?>(null)
    val activeLayerId: StateFlow<String?> = _activeLayerId.asStateFlow()

    private var nextZIndex = 0

    fun createLayer(name: String): AnnotationLayer {
        val layer = AnnotationLayer(
            id = UUID.randomUUID().toString(),
            name = name,
            zIndex = nextZIndex++
        )
        _layers.update { it + layer }
        if (_activeLayerId.value == null) {
            _activeLayerId.value = layer.id
        }
        return layer
    }

    fun deleteLayer(layerId: String) {
        _layers.update { it.filter { layer -> layer.id != layerId } }
        if (_activeLayerId.value == layerId) {
            _activeLayerId.value = _layers.value.firstOrNull()?.id
        }
    }

    fun setActiveLayer(layerId: String) {
        if (_layers.value.any { it.id == layerId }) {
            _activeLayerId.value = layerId
        }
    }

    fun setLayerVisibility(layerId: String, visible: Boolean) {
        _layers.update { layers ->
            layers.map { layer ->
                if (layer.id == layerId) layer.copy(isVisible = visible) else layer
            }
        }
    }

    fun setLayerOpacity(layerId: String, opacity: Float) {
        val clamped = opacity.coerceIn(0f, 1f)
        _layers.update { layers ->
            layers.map { layer ->
                if (layer.id == layerId) layer.copy(opacity = clamped) else layer
            }
        }
    }

    fun reorderLayer(layerId: String, newZIndex: Int) {
        _layers.update { layers ->
            val sorted = layers.sortedBy { it.zIndex }.toMutableList()
            val index = sorted.indexOfFirst { it.id == layerId }
            if (index == -1) return@update layers

            val layer = sorted.removeAt(index)
            sorted.add(newZIndex.coerceIn(0, sorted.size), layer)

            sorted.mapIndexed { i, l -> l.copy(zIndex = i) }
        }
    }

    fun getActiveLayer(): AnnotationLayer? {
        return _layers.value.find { it.id == _activeLayerId.value }
    }

    fun getAllAnnotations(): List<Annotation> {
        return _layers.value
            .sortedBy { it.zIndex }
            .flatMap { it.getVisibleAnnotations() }
    }

    fun getAnnotationsForPage(pageIndex: Int): List<Annotation> {
        return getAllAnnotations().filter { it.pageIndex == pageIndex }
    }

    fun lockLayer(layerId: String) {
        _layers.update { layers ->
            layers.map { layer ->
                if (layer.id == layerId) layer.copy(isLocked = true) else layer
            }
        }
    }

    fun unlockLayer(layerId: String) {
        _layers.update { layers ->
            layers.map { layer ->
                if (layer.id == layerId) layer.copy(isLocked = false) else layer
            }
        }
    }

    fun mergeLayers(sourceId: String, targetId: String) {
        val source = _layers.value.find { it.id == sourceId } ?: return
        val target = _layers.value.find { it.id == targetId } ?: return

        target.annotations.addAll(source.annotations)
        target.sortByZIndex()

        _layers.update { layers ->
            layers.filter { it.id != sourceId }
        }
    }
}
