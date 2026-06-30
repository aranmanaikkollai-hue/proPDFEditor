package com.propdf.annotations.history

import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.model.Annotation

/**
 * Command to delete an annotation from any layer.
 * Stores the original layer and index for perfect restoration.
 */
class DeleteAnnotationCommand(
    private val layerManager: LayerManager,
    private val annotation: Annotation
) : Command {

    private var layerId: String? = null
    private var index: Int = -1
    private var executed = false

    override fun execute() {
        for (layer in layerManager.layers.value) {
            val idx = layer.annotations.indexOfFirst { it.id == annotation.id }
            if (idx != -1) {
                layerId = layer.id
                index = idx
                layer.removeAnnotation(annotation.id)
                executed = true
                return
            }
        }
    }

    override fun undo() {
        if (executed && layerId != null && index != -1) {
            layerManager.layers.value
                .find { it.id == layerId }
                ?.annotations?.add(index.coerceAtMost(layerManager.layers.value.find { it.id == layerId }?.annotations?.size ?: 0), annotation)
        }
    }

    override fun getDescription(): String = "Delete ${annotation.type.name.lowercase().replace("_", " ")}"

    override fun getAffectedIds(): List<String> = listOf(annotation.id)
}
