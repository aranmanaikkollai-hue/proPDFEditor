package com.propdf.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.propdf.core.data.database.dao.*
import com.propdf.core.data.database.entity.*
import com.propdf.core.data.entity.BookmarkEntity
import com.propdf.core.data.entity.DocumentCollectionEntity
import com.propdf.core.data.entity.DocumentTagCrossRef
import com.propdf.core.data.entity.DocumentTagEntity
import com.propdf.core.data.entity.FormDataEntity
import com.propdf.core.data.entity.FormFieldEntity
import com.propdf.core.data.entity.PdfDocumentEntity
import com.propdf.core.data.entity.RecentActivityEntity
import com.propdf.core.data.local.dao.BookmarkDao
import com.propdf.core.data.local.dao.DocumentCollectionDao
import com.propdf.core.data.local.dao.DocumentTagDao
import com.propdf.core.data.local.dao.FormDataDao
import com.propdf.core.data.local.dao.FormFieldDao
import com.propdf.core.data.local.dao.PdfDocumentDao
import com.propdf.core.data.local.dao.RecentActivityDao

@Database(
    entities = [
        RecentFileEntity::class,
        OcrRecordEntity::class,
        SignatureEntity::class,
        ScanRecordEntity::class,
        CollectionEntity::class,
        ReadingProgressEntity::class,
        RecycleBinEntity::class,
        // v1 -> v4: the pdf_documents family. These entities already
        // declared @TypeConverters(Converters::class) — pointing at this
        // database's own converter class — and MIGRATION_2_3/MIGRATION_3_4
        // already existed to evolve them, but no @Database class actually
        // included them until now, so none of that migration path had ever
        // run. (The version-1 bookmarks table belonged to a separate,
        // never-consumed BookmarkRepository/BookmarkEntity pair that has
        // been retired — MIGRATION_3_4's "bookmarks" table is this one.)
        PdfDocumentEntity::class,
        DocumentTagEntity::class,
        DocumentTagCrossRef::class,
        DocumentCollectionEntity::class,
        RecentActivityEntity::class,
        FormFieldEntity::class,
        FormDataEntity::class,
        BookmarkEntity::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class ProPDFDatabase : RoomDatabase() {
    abstract fun recentFileDao(): RecentFileDao
    abstract fun ocrRecordDao(): OcrRecordDao
    abstract fun signatureDao(): SignatureDao
    abstract fun scanRecordDao(): ScanRecordDao
    abstract fun collectionDao(): CollectionDao
    abstract fun readingProgressDao(): ReadingProgressDao
    abstract fun recycleBinDao(): RecycleBinDao

    abstract fun pdfDocumentDao(): PdfDocumentDao
    abstract fun documentTagDao(): DocumentTagDao
    abstract fun documentCollectionDao(): DocumentCollectionDao
    abstract fun recentActivityDao(): RecentActivityDao
    abstract fun formFieldDao(): FormFieldDao
    abstract fun formDataDao(): FormDataDao
    abstract fun bookmarkDao(): BookmarkDao
}
