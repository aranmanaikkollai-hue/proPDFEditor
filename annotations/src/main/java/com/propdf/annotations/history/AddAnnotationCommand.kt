package com.propdf.annotations.history

import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.model.Annotation

class AddAnnotationCommand(
    private val layerManager: LayerManager,
    private val annotation: Annotation
) : Command {

    private var layerId: String? = null
    private var executed = false

    override fun execute() {
        val layer = layerManager.getActiveLayer()
        if (layer != null) {
            layerId = layer.id
            layer.addAnnotation(annotation)
            executed = true
        }
    }

    override fun undo() {
        if (executed && layerId != null) {
            layerManager.layers.value
                .find { it.id == layerId }
                ?.removeAnnotation(annotation.id)
        }
    }

    override fun getDescription(): String = "Add ${annotation.type.name.lowercase()}"
}
