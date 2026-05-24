package com.propdf.editor.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.propdf.editor.data.local.dao.*
import com.propdf.editor.data.local.entity.*

@Database(
    entities = [
        PdfDocumentEntity::class,
        FolderEntity::class,
        CloudAccountEntity::class,
        SearchIndexEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pdfDocumentDao(): PdfDocumentDao
    abstract fun folderDao(): FolderDao
    abstract fun cloudAccountDao(): CloudAccountDao
    abstract fun searchIndexDao(): SearchIndexDao
}
