package com.propdfeditor.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.core.data.database.MIGRATION_2_3
import com.propdf.core.data.database.MIGRATION_3_4
import com.propdf.core.data.database.ProPDFDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Upgrade tests for ProPDFDatabase (propdf_database): 2 -> 4 and 3 -> 4.
 *
 * Only the latest schema (4.json) is committed, so the older databases are built here from that schema:
 * version 3 is version 4 without the bookmarks table, and version 2 additionally lacks the five columns that
 * MIGRATION_2_3 adds to pdf_documents. That is an inference from the migration code, not a captured
 * database. Each path is tested twice: with the pdf_documents family already present, and with only the
 * original recent-files tables present (an installed database from before the family was added). After the
 * migrations run, MigrationTestHelper validates the result against 4.json.
 */
class ProPDFDatabaseMigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ProPDFDatabase::class.java
    )

    @After
    fun tearDown() {
        RoomSchemaTestSupport.deleteDatabase(DB)
    }

    private val schema4 by lazy { RoomSchemaTestSupport.load(ProPDFDatabase::class.java, 4) }

    private val newColumns = setOf("cloud_provider", "cloud_id", "sync_status", "document_type", "is_scanned")

    private fun legacyStatements(version: Int, withFamily: Boolean): List<String> {
        val statements = ArrayList<String>()
        for (entity in schema4.entities) {
            if (entity.table == "bookmarks") continue // added by 3 -> 4
            val isFamily = entity.table in FAMILY
            if (isFamily && !withFamily) continue
            val create = if (entity.table == "pdf_documents" && version == 2) {
                RoomSchemaTestSupport.withoutColumns(entity.createSql, newColumns)
            } else {
                entity.createSql
            }
            statements.add(create)
            statements.addAll(entity.indexSql)
        }
        return statements
    }

    private fun insertDocument(version: Int) {
        // Written through a plain connection so the test does not depend on Room entities or DAOs.
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(DB).path, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
        )
        try {
            db.execSQL(
                "INSERT INTO pdf_documents (uri_string, file_name, file_path, size_bytes, page_count, last_modified) " +
                    "VALUES ('content://x/doc1', 'doc1.pdf', '/p/doc1.pdf', 1234, 3, 99)"
            )
            if (version == 3) {
                db.execSQL("UPDATE pdf_documents SET cloud_provider = 'none', is_scanned = 1")
            }
        } finally {
            db.close()
        }
    }

    private fun assertMigrated(db: SupportSQLiteDatabase) {
        val tables = RoomSchemaTestSupport.tableNames(db)
        for (entity in schema4.entities) {
            assertTrue("missing table " + entity.table, entity.table in tables)
        }
        val columns = RoomSchemaTestSupport.columnNames(db, "pdf_documents")
        assertTrue("pdf_documents lacks new columns: " + columns, columns.containsAll(newColumns))
    }

    private fun countRows(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM " + table).use { it.moveToFirst(); it.getInt(0) }

    @Test
    fun v2WithDocumentFamily_migratesToV4_andKeepsRows() {
        RoomSchemaTestSupport.createLegacyDatabase(DB, 2, legacyStatements(2, withFamily = true))
        insertDocument(2)
        val db = helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_2_3, MIGRATION_3_4)
        assertMigrated(db)
        db.query("SELECT file_name, size_bytes, cloud_provider, is_scanned FROM pdf_documents").use {
            assertTrue(it.moveToFirst())
            assertEquals("doc1.pdf", it.getString(0))
            assertEquals(1234L, it.getLong(1))
            assertTrue(it.isNull(2))
            assertEquals(0, it.getInt(3))
        }
        assertEquals(0, countRows(db, "bookmarks"))
    }

    @Test
    fun v3WithDocumentFamily_migratesToV4_andKeepsRows() {
        RoomSchemaTestSupport.createLegacyDatabase(DB, 3, legacyStatements(3, withFamily = true))
        insertDocument(3)
        val db = helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4)
        assertMigrated(db)
        db.query("SELECT cloud_provider, is_scanned FROM pdf_documents").use {
            assertTrue(it.moveToFirst())
            assertEquals("none", it.getString(0))
            assertEquals(1, it.getInt(1))
        }
    }

    @Test
    fun v2WithoutDocumentFamily_migratesToV4() {
        RoomSchemaTestSupport.createLegacyDatabase(DB, 2, legacyStatements(2, withFamily = false))
        val db = helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_2_3, MIGRATION_3_4)
        assertMigrated(db)
        assertEquals(0, countRows(db, "pdf_documents"))
    }

    @Test
    fun v3WithoutDocumentFamily_migratesToV4() {
        RoomSchemaTestSupport.createLegacyDatabase(DB, 3, legacyStatements(3, withFamily = false))
        val db = helper.runMigrationsAndValidate(DB, 4, true, MIGRATION_3_4)
        assertMigrated(db)
        assertEquals(0, countRows(db, "pdf_documents"))
    }

    companion object {
        private const val DB = "migration-test-propdf-database"
        private val FAMILY = setOf(
            "pdf_documents", "document_tags", "document_tag_cross_ref", "document_collections",
            "recent_activities", "form_fields", "form_data"
        )
    }
}
