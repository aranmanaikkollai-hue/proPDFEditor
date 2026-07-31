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

/**
 * v3 -> v4: adds the `bookmarks` table.
 *
 * Bookmarks previously existed only as an in-memory Set<Int> inside
 * ViewerActivity (viewer/ui/ViewerActivity.kt's old `bookmarkedPages`
 * field) with no toggle UI and no persistence at all — every bookmark was
 * silently lost on process death, and there was no way to add one in the
 * first place. This migration adds real storage for the feature; the
 * viewer-side toggle UI and load/save wiring is added alongside it.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                uri_string TEXT NOT NULL,
                page_index INTEGER NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_bookmarks_uri_string_page_index " +
                "ON bookmarks(uri_string, page_index)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_bookmarks_uri_string ON bookmarks(uri_string)"
        )
    }
}
