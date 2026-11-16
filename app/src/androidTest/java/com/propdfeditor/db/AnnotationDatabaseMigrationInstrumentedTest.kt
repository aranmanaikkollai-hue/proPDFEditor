package com.propdfeditor.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.propdf.annotations.persistence.AnnotationDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Upgrade test for AnnotationDatabase (annotations.db): 1 -> 2.
 *
 * Only 2.json is committed, so version 1 is built here as version 2 without the documentPath and isFlattened
 * columns that MIGRATION_1_2 adds. That is an inference from the migration code, not a captured database.
 * The test lives in :app because :annotations has no androidTest source set and the emulator job only
 * targets :app, :core and :viewer.
 */
class AnnotationDatabaseMigrationInstrumentedTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AnnotationDatabase::class.java
    )

    @After
    fun tearDown() {
        RoomSchemaTestSupport.deleteDatabase(DB)
    }

    @Test
    fun v1_migratesToV2_andKeepsRows() {
        val schema2 = RoomSchemaTestSupport.load(AnnotationDatabase::class.java, 2)
        val entity = schema2.entity("annotations")
        val v1Create = RoomSchemaTestSupport.withoutColumns(entity.createSql, setOf("documentPath", "isFlattened"))
        RoomSchemaTestSupport.createLegacyDatabase(DB, 1, listOf(v1Create) + entity.indexSql)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(DB).path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            raw.execSQL(
                "INSERT INTO annotations (id, documentId, pageIndex, type, jsonData, zIndex, createdAt, modifiedAt, isVisible) " +
                    "VALUES ('a1', 'doc-1', 0, 'highlight', '{}', 1, 10, 20, 1)"
            )
        } finally {
            raw.close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, AnnotationDatabase.MIGRATION_1_2)
        db.query("SELECT id, documentId, documentPath, isFlattened, isVisible FROM annotations").use {
            assertTrue(it.moveToFirst())
            assertEquals("a1", it.getString(0))
            assertEquals("doc-1", it.getString(1))
            assertEquals("", it.getString(2))
            assertEquals(0, it.getInt(3))
            assertEquals(1, it.getInt(4))
        }
    }

    companion object {
        private const val DB = "migration-test-annotations"
    }
}
