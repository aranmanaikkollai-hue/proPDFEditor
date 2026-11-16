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
 * no longer an empty, unused table - RecentFilesRepositoryImpl's dual-write
 * (see the database consolidation plan) actively keeps it populated from
 * every file a user opens, every device scan, and every cloud sync. A
 * destructive wipe here would throw away real, current data.
 *
 * Phase 1 hardening: the pdf_documents family of tables was added to this
 * database class late, so an installed v2 or v3 database may not contain
 * pdf_documents at all. A bare ALTER TABLE on a missing table throws, and
 * Room does not fall back to a destructive migration for an exception thrown
 * inside a migration, so the app would fail to open its database. Each step
 * is therefore guarded: columns are added only when the table exists and the
 * column is missing, and MIGRATION_3_4 creates any table of the family that
 * is still absent using the exact version 4 definitions. Existing rows are
 * never touched.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        if (!db.tableExists("pdf_documents")) return
        db.addColumnIfMissing("pdf_documents", "cloud_provider", "TEXT")
        db.addColumnIfMissing("pdf_documents", "cloud_id", "TEXT")
        db.addColumnIfMissing("pdf_documents", "sync_status", "TEXT")
        db.addColumnIfMissing("pdf_documents", "document_type", "TEXT")
        db.addColumnIfMissing("pdf_documents", "is_scanned", "INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * v3 -> v4: adds the `bookmarks` table.
 *
 * Bookmarks previously existed only as an in-memory Set<Int> inside
 * ViewerActivity (viewer/ui/ViewerActivity.kt's old `bookmarkedPages`
 * field) with no toggle UI and no persistence at all - every bookmark was
 * silently lost on process death, and there was no way to add one in the
 * first place. This migration adds real storage for the feature; the
 * viewer-side toggle UI and load/save wiring is added alongside it.
 *
 * Also creates, only when absent, the rest of the pdf_documents family
 * (see MIGRATION_2_3). The statements are copied from the exported
 * version 4 schema (core/schemas/.../4.json).
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
        for (sql in DOCUMENT_FAMILY_V4_SQL) {
            db.execSQL(sql)
        }
    }
}

private fun SupportSQLiteDatabase.tableExists(table: String): Boolean {
    query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf<Any>(table)).use {
        return it.moveToFirst()
    }
}

private fun SupportSQLiteDatabase.addColumnIfMissing(table: String, column: String, definition: String) {
    var present = false
    query("PRAGMA table_info(" + table + ")").use {
        val nameIndex = it.getColumnIndex("name")
        while (it.moveToNext()) {
            if (it.getString(nameIndex) == column) present = true
        }
    }
    if (!present) {
        execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition)
    }
}

// Generated from core/schemas/com.propdf.core.data.database.ProPDFDatabase/4.json. All statements are
// idempotent (IF NOT EXISTS).
private val DOCUMENT_FAMILY_V4_SQL = listOf(
        "CREATE TABLE IF NOT EXISTS `pdf_documents` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `uri_string` TEXT NOT NULL, `file_name` TEXT NOT NULL, `file_path` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `page_count` INTEGER NOT NULL, `last_modified` INTEGER NOT NULL, `last_opened` INTEGER, `is_favorite` INTEGER NOT NULL DEFAULT 0, `is_hidden` INTEGER NOT NULL DEFAULT 0, `is_in_recycle_bin` INTEGER NOT NULL DEFAULT 0, `deleted_at` INTEGER, `thumbnail_uri` TEXT, `collection_id` INTEGER, `checksum` TEXT, `metadata_title` TEXT, `metadata_author` TEXT, `metadata_subject` TEXT, `metadata_keywords` TEXT, `metadata_creation_date` INTEGER, `cloud_provider` TEXT, `cloud_id` TEXT, `sync_status` TEXT, `document_type` TEXT, `is_scanned` INTEGER NOT NULL DEFAULT 0)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_pdf_documents_uri_string` ON `pdf_documents` (`uri_string`)",
        "CREATE INDEX IF NOT EXISTS `index_pdf_documents_file_path` ON `pdf_documents` (`file_path`)",
        "CREATE INDEX IF NOT EXISTS `index_pdf_documents_is_favorite` ON `pdf_documents` (`is_favorite`)",
        "CREATE INDEX IF NOT EXISTS `index_pdf_documents_is_hidden` ON `pdf_documents` (`is_hidden`)",
        "CREATE INDEX IF NOT EXISTS `index_pdf_documents_is_in_recycle_bin` ON `pdf_documents` (`is_in_recycle_bin`)",
        "CREATE INDEX IF NOT EXISTS `index_pdf_documents_last_opened` ON `pdf_documents` (`last_opened`)",
        "CREATE INDEX IF NOT EXISTS `index_pdf_documents_last_modified` ON `pdf_documents` (`last_modified`)",
        "CREATE INDEX IF NOT EXISTS `index_pdf_documents_size_bytes` ON `pdf_documents` (`size_bytes`)",
        "CREATE INDEX IF NOT EXISTS `index_pdf_documents_checksum` ON `pdf_documents` (`checksum`)",
        "CREATE INDEX IF NOT EXISTS `index_pdf_documents_collection_id` ON `pdf_documents` (`collection_id`)",
        "CREATE TABLE IF NOT EXISTS `document_tags` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `color` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_document_tags_name` ON `document_tags` (`name`)",
        "CREATE TABLE IF NOT EXISTS `document_tag_cross_ref` (`document_id` INTEGER NOT NULL, `tag_id` INTEGER NOT NULL, PRIMARY KEY(`document_id`, `tag_id`))",
        "CREATE INDEX IF NOT EXISTS `index_document_tag_cross_ref_document_id` ON `document_tag_cross_ref` (`document_id`)",
        "CREATE INDEX IF NOT EXISTS `index_document_tag_cross_ref_tag_id` ON `document_tag_cross_ref` (`tag_id`)",
        "CREATE TABLE IF NOT EXISTS `document_collections` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `description` TEXT, `color` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `cover_uri` TEXT)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_document_collections_name` ON `document_collections` (`name`)",
        "CREATE TABLE IF NOT EXISTS `recent_activities` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `document_id` INTEGER NOT NULL, `document_name` TEXT NOT NULL, `action` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `details` TEXT)",
        "CREATE INDEX IF NOT EXISTS `index_recent_activities_document_id` ON `recent_activities` (`document_id`)",
        "CREATE INDEX IF NOT EXISTS `index_recent_activities_timestamp` ON `recent_activities` (`timestamp`)",
        "CREATE TABLE IF NOT EXISTS `form_fields` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `documentUri` TEXT NOT NULL, `fieldName` TEXT NOT NULL, `fieldType` TEXT NOT NULL, `pageIndex` INTEGER NOT NULL, `rectLeft` REAL NOT NULL, `rectTop` REAL NOT NULL, `rectRight` REAL NOT NULL, `rectBottom` REAL NOT NULL, `value` TEXT, `defaultValue` TEXT, `optionsJson` TEXT, `isRequired` INTEGER NOT NULL, `isReadOnly` INTEGER NOT NULL, `fontSize` REAL NOT NULL, `textColor` INTEGER NOT NULL, `backgroundColor` INTEGER, `borderColor` INTEGER, `borderWidth` REAL NOT NULL, `rotation` INTEGER NOT NULL, `groupName` TEXT, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_form_fields_documentUri_fieldName` ON `form_fields` (`documentUri`, `fieldName`)",
        "CREATE TABLE IF NOT EXISTS `form_data` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `documentUri` TEXT NOT NULL, `fieldName` TEXT NOT NULL, `fieldValue` TEXT NOT NULL, `filledAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_form_data_documentUri` ON `form_data` (`documentUri`)",
)
