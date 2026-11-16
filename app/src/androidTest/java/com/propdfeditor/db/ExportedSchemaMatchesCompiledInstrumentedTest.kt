package com.propdfeditor.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * For every Room database that exports a schema, opens the compiled database in memory and compares its
 * identity hash with the hash recorded in the exported JSON for the same version. A mismatch means the
 * committed schema file is stale (an entity changed without a version bump or without re-exporting), or the
 * JSON for the current version is missing. Databases with exportSchema = false are not covered here:
 * app RecentFilesDatabase, viewer AnnotationDatabase and ViewerDatabase, core SearchDatabase.
 */
class ExportedSchemaMatchesCompiledInstrumentedTest {

    private val databases: List<Class<out RoomDatabase>> = listOf(
        com.propdf.annotations.persistence.AnnotationDatabase::class.java,
        com.propdf.editor.data.local.AppDatabase::class.java,
        com.propdf.editor.data.local.db.AppDatabase::class.java,
        com.propdfeditor.batch.data.database.BatchDatabase::class.java,
        com.propdf.core.data.database.ProPDFDatabase::class.java,
        com.propdf.core.data.local.OcrDatabase::class.java,
        com.propdf.core.data.local.RecentFilesDatabase::class.java,
        com.propdfeditor.core.database.AppDatabase::class.java,
        com.propdf.nas.data.local.NasDatabase::class.java,
        com.propdf.scanner.data.local.ScannerDatabase::class.java,
        com.propdf.security.data.database.SecurityDatabase::class.java,
        com.propdf.storage.data.local.StorageDatabase::class.java,
        com.propdf.sync.data.local.SyncDatabase::class.java
    )

    @Test
    fun everyExportedSchemaMatchesTheCompiledDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failures = ArrayList<String>()
        for (cls in databases) {
            val name = cls.canonicalName ?: cls.name
            try {
                @Suppress("UNCHECKED_CAST")
                val db = Room.inMemoryDatabaseBuilder(context, cls as Class<RoomDatabase>).allowMainThreadQueries().build()
                try {
                    val sqlite = db.openHelper.writableDatabase
                    val version = sqlite.version
                    var compiledHash: String? = null
                    sqlite.query("SELECT identity_hash FROM room_master_table WHERE id = 42").use {
                        if (it.moveToFirst()) compiledHash = it.getString(0)
                    }
                    val exported = RoomSchemaTestSupport.load(cls, version)
                    if (exported.version != version) failures.add(name + ": json version " + exported.version + " != " + version)
                    if (compiledHash != exported.identityHash) {
                        failures.add(name + " v" + version + ": compiled hash " + compiledHash + " != exported " + exported.identityHash)
                    }
                } finally {
                    db.close()
                }
            } catch (e: Exception) {
                failures.add(name + ": " + e.javaClass.simpleName + ": " + e.message)
            }
        }
        assertTrue("Schema export problems:\n" + failures.joinToString("\n"), failures.isEmpty())
        assertEquals(13, databases.size)
    }
}
