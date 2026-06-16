// annotations/src/main/java/com/propdf/annotations/persistence/AnnotationDao.kt
package com.propdf.annotations.persistence

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AnnotationDao {

    @Query("SELECT * FROM annotations WHERE documentId = :documentId ORDER BY zIndex, createdAt")
    fun getAnnotationsForDocument(documentId: String): Flow<List<<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE documentId = :documentId AND pageIndex = :pageIndex ORDER BY zIndex")
    suspend fun getAnnotationsForPage(documentId: String, pageIndex: Int): List<<AnnotationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnotation(entity: AnnotationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnnotations(entities: List<<AnnotationEntity>)

    @Delete
    suspend fun deleteAnnotation(entity: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE documentId = :documentId")
    suspend fun deleteAnnotationsForDocument(documentId: String)

    @Query("DELETE FROM annotations WHERE id = :annotationId")
    suspend fun deleteAnnotationById(annotationId: String)

    @Query("SELECT COUNT(*) FROM annotations WHERE documentId = :documentId")
    suspend fun getAnnotationCount(documentId: String): Int
}
