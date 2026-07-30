package com.propdf.core.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v2 -> v3: adds cloud-sync (cloud_provider/cloud_id/sync_status) and
 * document-classification (document_type/is_scanned) columns to
 * pdf_documents.
 *
 * This is a real ALTER TABLE migration rather than relying on
 * fallbackToDestructiveMigration(), because by this point pdf_documents is
 * no longer an empty, unused table — RecentFilesRepositoryImpl's dual-write
 * (see the database consolidation plan) actively keeps it populated from
 * every file a user opens, every device scan, and every cloud sync. A
 * destructive wipe here would throw away real, current data.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pdf_documents ADD COLUMN cloud_provider TEXT")
        db.execSQL("ALTER TABLE pdf_documents ADD COLUMN cloud_id TEXT")
        db.execSQL("ALTER TABLE pdf_documents ADD COLUMN sync_status TEXT")
        db.execSQL("ALTER TABLE pdf_documents ADD COLUMN document_type TEXT")
        db.execSQL("ALTER TABLE pdf_documents ADD COLUMN is_scanned INTEGER NOT NULL DEFAULT 0")
    }
}
