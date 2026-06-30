package com.propdf.annotations.history

import com.propdf.annotations.layers.LayerManager
import com.propdf.annotations.model.Annotation

/**
 * Command to move annotations by delta.
 * Optimized for frequent move operations (dragging).
 */
class MoveAnnotationCommand(
    private val layerManager: LayerManager,
    private val annotations: List<Annotation>,
    private val dx: Float,
    private val dy: Float
) : Command {

    private var executed = false
    private val originalPositions = annotations.map { it.getBounds() }

    override fun execute() {
        annotations.forEach { annotation ->
            val moved = annotation.translate(dx, dy)
            for (layer in layerManager.layers.value) {
                val idx = layer.annotations.indexOfFirst { it.id == annotation.id }
                if (idx != -1) {
                    layer.annotations[idx] = moved
                    break
                }
            }
        }
        executed = true
    }

    override fun undo() {
        if (!executed) return
        annotations.forEachIndexed { index, annotation ->
            val bounds = originalPositions[index]
            val currentDx = bounds.left - annotation.getBounds().left
            val currentDy = bounds.top - annotation.getBounds().top
            val restored = annotation.translate(currentDx, currentDy)
            for (layer in layerManager.layers.value) {
                val idx = layer.annotations.indexOfFirst { it.id == annotation.id }
                if (idx != -1) {
                    layer.annotations[idx] = restored
                    break
                }
            }
        }
    }

    override fun getDescription(): String = "Move ${annotations.size} annotation${if (annotations.size > 1) "s" else ""}"

    override fun getAffectedIds(): List<String> = annotations.map { it.id }
}
