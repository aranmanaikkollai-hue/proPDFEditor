package com.propdf.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.propdf.core.data.entity.*
import com.propdf.core.data.local.dao.*

@Database(
    entities = [
        PdfDocumentEntity::class,
        DocumentTagEntity::class,
        DocumentTagCrossRef::class,
        DocumentCollectionEntity::class,
        RecentActivityEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class ProPDFDatabase : RoomDatabase() {
    abstract fun pdfDocumentDao(): PdfDocumentDao
    abstract fun documentTagDao(): DocumentTagDao
    abstract fun documentCollectionDao(): DocumentCollectionDao
    abstract fun recentActivityDao(): RecentActivityDao
}
