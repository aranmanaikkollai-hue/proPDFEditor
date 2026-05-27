package com.propdf.viewer.annotation.persistence

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [AnnotationEntity::class], version = 1, exportSchema = false)
abstract class AnnotationDatabase : RoomDatabase() {
    abstract fun annotationDao(): AnnotationDao
}
