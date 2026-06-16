// annotations/src/main/java/com/propdf/annotations/history/DeleteAnnotationCommand.kt
package com.propdf.annotations.history

import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.model.Annotation

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
                ?.annotations?.add(index, annotation)
        }
    }

    override fun getDescription(): String = "Delete ${annotation.type.name.lowercase()}"
}
