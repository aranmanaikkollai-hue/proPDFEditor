package com.propdfeditor.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdf.core.data.database.ProPDFDatabase
import com.propdf.core.data.entity.PdfDocumentEntity
import com.propdf.core.data.local.RecentFilesDatabase
import com.propdf.core.data.local.RecentFilesRepositoryImpl
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFileInsertResult
import com.propdf.core.domain.repository.RecentFileLookup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Room (in-memory) coverage of the lookup / insert-if-absent contract used by the document scan.
 * Requires an emulator or device; NOT RUN until executed in CI (android-tests.yml, :core:connectedDebugAndroidTest).
 */
@RunWith(AndroidJUnit4::class)
class RecentFilesRepositoryLookupInstrumentedTest {

    private lateinit var recentDb: RecentFilesDatabase
    private lateinit var mainDb: ProPDFDatabase
    private lateinit var repo: RecentFilesRepositoryImpl

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        recentDb = Room.inMemoryDatabaseBuilder(ctx, RecentFilesDatabase::class.java).allowMainThreadQueries().build()
        mainDb = Room.inMemoryDatabaseBuilder(ctx, ProPDFDatabase::class.java).allowMainThreadQueries().build()
        repo = RecentFilesRepositoryImpl(recentDb.recentFileDao(), mainDb.pdfDocumentDao())
    }

    @After
    fun tearDown() {
        recentDb.close()
        mainDb.close()
    }

    private fun file(uri: String, fav: Boolean = false, cat: String = "", opened: Long = 100L, name: String = "a.pdf") =
        RecentFile(uri = uri, name = name, size = 1, lastOpened = opened, pageCount = 0, isFavorite = fav, category = cat)

    @Test
    fun lookup_unknownUri_isNotFound() = runBlocking {
        assertEquals(RecentFileLookup.NotFound, repo.lookupByUri("content://x/1"))
    }

    @Test
    fun lookup_existingUri_isFound() = runBlocking {
        repo.addIfAbsent(file("content://x/1", fav = true, cat = "Tax"))
        val r = repo.lookupByUri("content://x/1")
        assertTrue(r is RecentFileLookup.Found)
        assertEquals("Tax", (r as RecentFileLookup.Found).file.category)
    }

    @Test
    fun addIfAbsent_newRow_isInserted() = runBlocking {
        assertEquals(RecentFileInsertResult.Inserted, repo.addIfAbsent(file("content://x/1")))
    }

    @Test
    fun addIfAbsent_existingRow_isNeverOverwritten() = runBlocking {
        repo.addIfAbsent(file("content://x/1", fav = true, cat = "Tax", opened = 5_000L, name = "Mine.pdf"))
        val second = repo.addIfAbsent(file("content://x/1", fav = false, cat = "", opened = 1L, name = "scan.pdf"))
        assertEquals(RecentFileInsertResult.AlreadyExists, second)
        val row = (repo.lookupByUri("content://x/1") as RecentFileLookup.Found).file
        assertTrue(row.isFavorite)
        assertEquals("Tax", row.category)
        assertEquals(5_000L, row.lastOpened)
        assertEquals("Mine.pdf", row.name)
    }

    @Test
    fun addIfAbsent_doesNotModifyAnExistingMirroredDocumentRow() = runBlocking {
        val dao = mainDb.pdfDocumentDao()
        dao.insert(
            PdfDocumentEntity(
                uriString = "content://x/1", fileName = "Mine.pdf", filePath = "content://x/1",
                sizeBytes = 9, pageCount = 3, lastModified = 1, lastOpened = 5_000L, isFavorite = true
            )
        )
        repo.addIfAbsent(file("content://x/1", fav = false, opened = 1L))
        val mirrored = dao.getByUri("content://x/1")!!
        assertTrue(mirrored.isFavorite)
        assertEquals(5_000L, mirrored.lastOpened)
        assertEquals("Mine.pdf", mirrored.fileName)
    }

    @Test
    fun repeatedAddIfAbsent_doesNotDuplicate() = runBlocking {
        repeat(3) { repo.addIfAbsent(file("content://x/1")) }
        val rows = recentDb.recentFileDao().getAll()
        // Flow-based DAO: take the first emission
        val list = rows.first()
        assertEquals(1, list.size)
    }

    @Test
    fun lookup_databaseError_isFailed_notNotFound() = runBlocking {
        recentDb.openHelper.writableDatabase.execSQL("DROP TABLE recent_files")
        val r = repo.lookupByUri("content://x/1")
        assertTrue("a database error must not be reported as NotFound, was $r", r is RecentFileLookup.Failed)
    }

    @Test
    fun addIfAbsent_databaseError_isFailed() = runBlocking {
        recentDb.openHelper.writableDatabase.execSQL("DROP TABLE recent_files")
        assertTrue(repo.addIfAbsent(file("content://x/1")) is RecentFileInsertResult.Failed)
    }
}
