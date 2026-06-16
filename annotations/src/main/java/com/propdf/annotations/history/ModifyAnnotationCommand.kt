// annotations/src/main/java/com/propdf/annotations/history/ModifyAnnotationCommand.kt
package com.propdf.annotations.history

import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.model.Annotation

class ModifyAnnotationCommand(
    private val layerManager: LayerManager,
    private val oldAnnotation: Annotation,
    private val newAnnotation: Annotation
) : Command {

    private var executed = false

    override fun execute() {
        for (layer in layerManager.layers.value) {
            val idx = layer.annotations.indexOfFirst { it.id == oldAnnotation.id }
            if (idx != -1) {
                layer.annotations[idx] = newAnnotation
                executed = true
                return
            }
        }
    }

    override fun undo() {
        if (executed) {
            for (layer in layerManager.layers.value) {
                val idx = layer.annotations.indexOfFirst { it.id == newAnnotation.id }
                if (idx != -1) {
                    layer.annotations[idx] = oldAnnotation
                    return
                }
            }
        }
    }

    override fun getDescription(): String = "Modify ${oldAnnotation.type.name.lowercase()}"
}
