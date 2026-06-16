// annotations/src/main/java/com/propdf/annotations/persistence/AnnotationRepository.kt
package com.propdf.annotations.persistence

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.propdf.annotations.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository for annotation CRUD operations with JSON serialization.
 */
class AnnotationRepository(
    private val dao: AnnotationDao,
    private val gson: Gson = Gson()
) {

    fun getAnnotationsForDocument(documentId: String): Flow<List<<Annotation>> {
        return dao.getAnnotationsForDocument(documentId).map { entities ->
            entities.mapNotNull { deserialize(it) }
        }
    }

    suspend fun getAnnotationsForPage(documentId: String, pageIndex: Int): List<<Annotation> = withContext(Dispatchers.IO) {
        dao.getAnnotationsForPage(documentId, pageIndex).mapNotNull { deserialize(it) }
    }

    suspend fun saveAnnotation(documentId: String, annotation: Annotation) = withContext(Dispatchers.IO) {
        val entity = serialize(documentId, annotation)
        dao.insertAnnotation(entity)
    }

    suspend fun saveAnnotations(documentId: String, annotations: List<<Annotation>) = withContext(Dispatchers.IO) {
        val entities = annotations.map { serialize(documentId, it) }
        dao.insertAnnotations(entities)
    }

    suspend fun deleteAnnotation(annotationId: String) = withContext(Dispatchers.IO) {
        dao.deleteAnnotationById(annotationId)
    }

    suspend fun deleteAnnotationsForDocument(documentId: String) = withContext(Dispatchers.IO) {
        dao.deleteAnnotationsForDocument(documentId)
    }

    private fun serialize(documentId: String, annotation: Annotation): AnnotationEntity {
        val jsonData = when (annotation) {
            is StrokeAnnotation -> gson.toJson(annotation)
            is ShapeAnnotation -> gson.toJson(annotation)
            is TextAnnotation -> gson.toJson(annotation)
            is HighlightAnnotation -> gson.toJson(annotation)
            else -> throw IllegalArgumentException("Unknown annotation type")
        }

        return AnnotationEntity(
            id = annotation.id,
            documentId = documentId,
            pageIndex = annotation.pageIndex,
            type = annotation.type.name,
            jsonData = jsonData,
            zIndex = annotation.zIndex,
            createdAt = annotation.createdAt,
            modifiedAt = annotation.modifiedAt,
            isVisible = annotation.isVisible
        )
    }

    private fun deserialize(entity: AnnotationEntity): Annotation? {
        return try {
            when (entity.type) {
                AnnotationType.INK.name,
                AnnotationType.SIGNATURE.name -> gson.fromJson(entity.jsonData, StrokeAnnotation::class.java)

                AnnotationType.RECTANGLE.name,
                AnnotationType.CIRCLE.name,
                AnnotationType.LINE.name,
                AnnotationType.ARROW.name -> gson.fromJson(entity.jsonData, ShapeAnnotation::class.java)

                AnnotationType.TEXT.name -> gson.fromJson(entity.jsonData, TextAnnotation::class.java)

                AnnotationType.HIGHLIGHT.name,
                AnnotationType.UNDERLINE.name,
                AnnotationType.STRIKEOUT.name -> gson.fromJson(entity.jsonData, HighlightAnnotation::class.java)

                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
