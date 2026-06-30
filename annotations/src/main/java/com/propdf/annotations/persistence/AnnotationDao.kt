package com.propdf.annotations.persistence

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for annotation CRUD operations.
 * Supports both reactive (Flow) and suspend query patterns.
 */
@Dao
interface AnnotationDao {

    @Query("SELECT * FROM annotations WHERE documentId = :documentId ORDER BY zIndex, createdAt")
    fun getAnnotationsForDocument(documentId: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE documentId = :documentId AND pageIndex = :pageIndex ORDER BY zIndex")
    suspend fun getAnnotationsForPage(documentId: String, pageIndex: Int): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE documentId = :documentId AND type = :type ORDER BY zIndex")
    suspend fun getAnnotationsByType(documentId: String, type: String): List<AnnotationEntity>

    @Query("SELECT * FROM annotations WHERE id = :annotationId LIMIT 1")
    suspend fun getAnnotationById(annotationId: String): AnnotationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnotation(entity: AnnotationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnotations(entities: List<AnnotationEntity>)

    @Update
    suspend fun updateAnnotation(entity: AnnotationEntity)

    @Delete
    suspend fun deleteAnnotation(entity: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE documentId = :documentId")
    suspend fun deleteAnnotationsForDocument(documentId: String)

    @Query("DELETE FROM annotations WHERE id = :annotationId")
    suspend fun deleteAnnotationById(annotationId: String)

    @Query("DELETE FROM annotations WHERE documentId = :documentId AND pageIndex = :pageIndex")
    suspend fun deleteAnnotationsForPage(documentId: String, pageIndex: Int)

    @Query("SELECT COUNT(*) FROM annotations WHERE documentId = :documentId")
    suspend fun getAnnotationCount(documentId: String): Int

    @Query("SELECT COUNT(*) FROM annotations WHERE documentId = :documentId AND pageIndex = :pageIndex")
    suspend fun getAnnotationCountForPage(documentId: String, pageIndex: Int): Int

    @Query("SELECT * FROM annotations WHERE documentId = :documentId AND isFlattened = 0 ORDER BY zIndex, createdAt")
    suspend fun getUnflattenedAnnotations(documentId: String): List<AnnotationEntity>

    @Query("UPDATE annotations SET isFlattened = 1 WHERE documentId = :documentId")
    suspend fun markAnnotationsFlattened(documentId: String)

    @Query("SELECT DISTINCT pageIndex FROM annotations WHERE documentId = :documentId ORDER BY pageIndex")
    suspend fun getAnnotatedPages(documentId: String): List<Int>
}
