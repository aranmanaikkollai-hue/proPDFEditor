package com.propdf.editor.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.propdf.editor.data.local.dao.*
import com.propdf.editor.data.local.entity.*

@Database(
    entities = [
        PdfDocumentEntity::class,
        FolderEntity::class,
        CloudAccountEntity::class,
        SearchIndexEntity::class,
        CollectionEntity::class,
        TagEntity::class,
        DocumentCollectionCrossRef::class,
        DocumentTagCrossRef::class,
        FileHashEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pdfDocumentDao(): PdfDocumentDao
    abstract fun folderDao(): FolderDao
    abstract fun cloudAccountDao(): CloudAccountDao
    abstract fun searchIndexDao(): SearchIndexDao
    abstract fun collectionDao(): CollectionDao
    abstract fun tagDao(): TagDao
    abstract fun fileHashDao(): FileHashDao
}
