package com.propdf.annotations.persistence

import android.graphics.RectF
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.propdf.annotations.model.*
import com.propdf.annotations.model.Annotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Repository for annotation CRUD operations with JSON serialization.
 * Handles all annotation types with type-safe deserialization.
 *
 * Thread-safe: All operations run on Dispatchers.IO.
 */
class AnnotationRepository(
    private val dao: AnnotationDao,
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(RectF::class.java, RectFAdapter())
        .create()
) {

    /**
     * Observe all annotations for a document as a Flow.
     */
    fun getAnnotationsForDocument(documentId: String): Flow<List<Annotation>> {
        return dao.getAnnotationsForDocument(documentId).map { entities ->
            entities.mapNotNull { deserialize(it) }
        }
    }

    /**
     * Get annotations for a specific page (one-shot).
     */
    suspend fun getAnnotationsForPage(documentId: String, pageIndex: Int): List<Annotation> =
        withContext(Dispatchers.IO) {
            dao.getAnnotationsForPage(documentId, pageIndex).mapNotNull { deserialize(it) }
        }

    /**
     * Get annotations by type.
     */
    suspend fun getAnnotationsByType(documentId: String, type: AnnotationType): List<Annotation> =
        withContext(Dispatchers.IO) {
            dao.getAnnotationsByType(documentId, type.name).mapNotNull { deserialize(it) }
        }

    /**
     * Get a single annotation by ID.
     */
    suspend fun getAnnotationById(annotationId: String): Annotation? =
        withContext(Dispatchers.IO) {
            dao.getAnnotationById(annotationId)?.let { deserialize(it) }
        }

    /**
     * Save a single annotation.
     */
    suspend fun saveAnnotation(documentId: String, documentPath: String, annotation: Annotation) =
        withContext(Dispatchers.IO) {
            val entity = serialize(documentId, documentPath, annotation)
            dao.insertAnnotation(entity)
        }

    /**
     * Save multiple annotations in a batch transaction.
     */
    suspend fun saveAnnotations(documentId: String, documentPath: String, annotations: List<Annotation>) =
        withContext(Dispatchers.IO) {
            val entities = annotations.map { serialize(documentId, documentPath, it) }
            dao.insertAnnotations(entities)
        }

    /**
     * Update an existing annotation.
     */
    suspend fun updateAnnotation(documentId: String, documentPath: String, annotation: Annotation) =
        withContext(Dispatchers.IO) {
            val entity = serialize(documentId, documentPath, annotation)
            dao.updateAnnotation(entity)
        }

    /**
     * Delete a single annotation by ID.
     */
    suspend fun deleteAnnotation(annotationId: String) =
        withContext(Dispatchers.IO) {
            dao.deleteAnnotationById(annotationId)
        }

    /**
     * Delete all annotations for a document.
     */
    suspend fun deleteAnnotationsForDocument(documentId: String) =
        withContext(Dispatchers.IO) {
            dao.deleteAnnotationsForDocument(documentId)
        }

    /**
     * Delete annotations for a specific page.
     */
    suspend fun deleteAnnotationsForPage(documentId: String, pageIndex: Int) =
        withContext(Dispatchers.IO) {
            dao.deleteAnnotationsForPage(documentId, pageIndex)
        }

    /**
     * Get total annotation count for a document.
     */
    suspend fun getAnnotationCount(documentId: String): Int =
        withContext(Dispatchers.IO) {
            dao.getAnnotationCount(documentId)
        }

    /**
     * Get count for a specific page.
     */
    suspend fun getAnnotationCountForPage(documentId: String, pageIndex: Int): Int =
        withContext(Dispatchers.IO) {
            dao.getAnnotationCountForPage(documentId, pageIndex)
        }

    /**
     * Get pages that have annotations.
     */
    suspend fun getAnnotatedPages(documentId: String): List<Int> =
        withContext(Dispatchers.IO) {
            dao.getAnnotatedPages(documentId)
        }

    /**
     * Get unflattened annotations (for PDF export).
     */
    suspend fun getUnflattenedAnnotations(documentId: String): List<Annotation> =
        withContext(Dispatchers.IO) {
            dao.getUnflattenedAnnotations(documentId).mapNotNull { deserialize(it) }
        }

    /**
     * Mark all annotations as flattened.
     */
    suspend fun markAnnotationsFlattened(documentId: String) =
        withContext(Dispatchers.IO) {
            dao.markAnnotationsFlattened(documentId)
        }

    /**
     * Export annotations to JSON file.
     */
    suspend fun exportToJson(documentId: String, outputFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val annotations = getAnnotationsForDocument(documentId).first()
                val exportData = AnnotationExportData(
                    documentId = documentId,
                    version = AnnotationEntity.CURRENT_VERSION,
                    annotations = annotations
                )
                outputFile.writeText(gson.toJson(exportData))
                true
            } catch (e: Exception) {
                false
            }
        }

    /**
     * Import annotations from JSON file.
     */
    suspend fun importFromJson(documentId: String, documentPath: String, jsonFile: File): List<Annotation> =
        withContext(Dispatchers.IO) {
            try {
                val json = jsonFile.readText()
                val exportData = gson.fromJson(json, AnnotationExportData::class.java)
                exportData?.annotations?.also { annotations ->
                    saveAnnotations(documentId, documentPath, annotations)
                } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

    // ==================== Serialization ====================

    private fun serialize(documentId: String, documentPath: String, annotation: Annotation): AnnotationEntity {
        val jsonData = when (annotation) {
            is StrokeAnnotation -> gson.toJson(annotation)
            is ShapeAnnotation -> gson.toJson(annotation)
            is TextAnnotation -> gson.toJson(annotation)
            is HighlightAnnotation -> gson.toJson(annotation)
            is StampAnnotation -> gson.toJson(annotation)
            is LassoAnnotation -> gson.toJson(annotation)
            else -> throw IllegalArgumentException("Unknown annotation type: ${annotation.type}")
        }

        return AnnotationEntity(
            id = annotation.id,
            documentId = documentId,
            documentPath = documentPath,
            pageIndex = annotation.pageIndex,
            type = annotation.type.name,
            jsonData = jsonData,
            zIndex = annotation.zIndex,
            createdAt = annotation.createdAt,
            modifiedAt = annotation.modifiedAt,
            isVisible = annotation.isVisible,
            isFlattened = false
        )
    }

    private fun deserialize(entity: AnnotationEntity): Annotation? {
        return try {
            when (entity.type) {
                AnnotationType.INK.name,
                AnnotationType.SIGNATURE.name,
                AnnotationType.PEN.name,
                AnnotationType.CALLIGRAPHY.name,
                AnnotationType.MARKER.name,
                AnnotationType.PENCIL.name,
                AnnotationType.ERASER.name -> gson.fromJson(entity.jsonData, StrokeAnnotation::class.java)

                AnnotationType.RECTANGLE.name,
                AnnotationType.CIRCLE.name,
                AnnotationType.LINE.name,
                AnnotationType.ARROW.name,
                AnnotationType.POLYGON.name,
                AnnotationType.CLOUD.name -> gson.fromJson(entity.jsonData, ShapeAnnotation::class.java)

                AnnotationType.TEXT.name,
                AnnotationType.FREE_TEXT.name,
                AnnotationType.STICKY_NOTE.name,
                AnnotationType.TEXTBOX.name -> gson.fromJson(entity.jsonData, TextAnnotation::class.java)

                AnnotationType.HIGHLIGHT.name,
                AnnotationType.UNDERLINE.name,
                AnnotationType.STRIKEOUT.name,
                AnnotationType.SQUIGGLY.name -> gson.fromJson(entity.jsonData, HighlightAnnotation::class.java)

                AnnotationType.STAMP.name,
                AnnotationType.DATE_STAMP.name -> gson.fromJson(entity.jsonData, StampAnnotation::class.java)

                AnnotationType.LASSO_SELECT.name -> gson.fromJson(entity.jsonData, LassoAnnotation::class.java)

                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Data class for JSON export/import.
     */
    private data class AnnotationExportData(
        val documentId: String,
        val version: Int,
        val exportDate: Long = System.currentTimeMillis(),
        val annotations: List<Annotation>
    )

    /**
     * Custom Gson adapter for RectF to handle Android's RectF serialization.
     */
    private class RectFAdapter : com.google.gson.TypeAdapter<RectF>() {
        override fun write(out: com.google.gson.stream.JsonWriter, value: RectF?) {
            if (value == null) {
                out.nullValue()
            } else {
                out.beginObject()
                out.name("left").value(value.left)
                out.name("top").value(value.top)
                out.name("right").value(value.right)
                out.name("bottom").value(value.bottom)
                out.endObject()
            }
        }

        override fun read(reader: com.google.gson.stream.JsonReader): RectF? {
            var left = 0f
            var top = 0f
            var right = 0f
            var bottom = 0f
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "left" -> left = reader.nextDouble().toFloat()
                    "top" -> top = reader.nextDouble().toFloat()
                    "right" -> right = reader.nextDouble().toFloat()
                    "bottom" -> bottom = reader.nextDouble().toFloat()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            return RectF(left, top, right, bottom)
        }
    }
}
