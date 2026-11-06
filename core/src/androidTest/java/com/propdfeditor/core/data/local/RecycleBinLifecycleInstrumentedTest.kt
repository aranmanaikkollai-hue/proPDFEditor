package com.propdfeditor.core.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdf.core.data.database.ProPDFDatabase
import com.propdf.core.data.entity.PdfDocumentEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated in-memory ProPDFDatabase (never a real user database): delete -> bin query -> restore -> purge. NOT RUN until CI. */
@RunWith(AndroidJUnit4::class)
class RecycleBinLifecycleInstrumentedTest {
    private lateinit var db: ProPDFDatabase
    private val dao get() = db.pdfDocumentDao()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), ProPDFDatabase::class.java)
            .allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close() }

    private fun doc(uri: String, fav: Boolean = false) = PdfDocumentEntity(
        uriString = uri, fileName = "$uri.pdf", filePath = uri, sizeBytes = 1, lastModified = 1, isFavorite = fav)

    @Test fun movedDocument_appearsInBin_withSameRow_andKeepsMetadata() = runBlocking {
        val id = dao.insert(doc("u1", fav = true))
        dao.moveToRecycleBin(id, 1_000L)
        val bin = dao.getRecycleBinDocuments().first()
        assertEquals(listOf(id), bin.map { it.id })
        assertEquals(1_000L, bin.single().deletedAt)
        assertTrue(bin.single().isFavorite)
    }

    @Test fun restoredDocument_leavesBin_andIsNotEligibleForPurge() = runBlocking {
        val id = dao.insert(doc("u1", fav = true))
        dao.moveToRecycleBin(id, 1_000L)
        dao.restoreFromRecycleBin(id)
        assertTrue(dao.getRecycleBinDocuments().first().isEmpty())
        dao.deleteOldRecycleBinItems(Long.MAX_VALUE)
        val row = dao.getById(id)
        assertNotNull(row); assertTrue(row!!.isFavorite); assertNull(row.deletedAt)
    }

    @Test fun purge_deletesOnlyOldBinRows_notRecent_notLive_notNullTimestamp() = runBlocking {
        val old = dao.insert(doc("old")); val recent = dao.insert(doc("recent"))
        val live = dao.insert(doc("live")); val nullTs = dao.insert(doc("nullts"))
        dao.moveToRecycleBin(old, 100L); dao.moveToRecycleBin(recent, 9_000L)
        dao.moveToRecycleBin(nullTs, 100L)
        db.openHelper.writableDatabase.execSQL("UPDATE pdf_documents SET deleted_at = NULL WHERE id = $nullTs")
        dao.deleteOldRecycleBinItems(5_000L)
        assertNull(dao.getById(old))
        assertNotNull(dao.getById(recent)); assertNotNull(dao.getById(live)); assertNotNull(dao.getById(nullTs))
    }

    @Test fun repeatedPurge_isIdempotent() = runBlocking {
        val id = dao.insert(doc("a")); val keep = dao.insert(doc("b", fav = true))
        dao.moveToRecycleBin(id, 100L)
        repeat(3) { dao.deleteOldRecycleBinItems(5_000L) }
        assertNull(dao.getById(id)); assertTrue(dao.getById(keep)!!.isFavorite)
    }

    @Test fun missingRecord_purgeOfAbsentId_changesNothing() = runBlocking {
        val id = dao.insert(doc("a"))
        dao.deletePermanently(9_999L)
        assertNotNull(dao.getById(id))
        assertFalse(dao.getRecycleBinDocuments().first().any { it.id == id })
    }

    @Test fun thresholdCases_1_29_30_60_90_days() = runBlocking {
        val day = 86_400_000L; val now = 10_000L * day
        val ages = listOf(10, 29, 30, 60)
        val ids = ages.associateWith { dao.insert(doc("age$it")) }
        ids.forEach { (age, id) -> dao.moveToRecycleBin(id, now - age * day) }
        // 30-day setting: strictly older than cutoff is purged; exactly 30 days is retained
        dao.deleteOldRecycleBinItems(now - 30 * day)
        assertNotNull(dao.getById(ids.getValue(10))); assertNotNull(dao.getById(ids.getValue(29)))
        assertNotNull(dao.getById(ids.getValue(30))); assertNull(dao.getById(ids.getValue(60)))
        // 1-day setting purges the rest that are older than one day
        dao.deleteOldRecycleBinItems(now - 1 * day)
        assertNull(dao.getById(ids.getValue(10))); assertNull(dao.getById(ids.getValue(29))); assertNull(dao.getById(ids.getValue(30)))
    }

    @Test fun ninetyDaySetting_keepsItemsYoungerThan90Days() = runBlocking {
        val day = 86_400_000L; val now = 10_000L * day
        val young = dao.insert(doc("y")); val old = dao.insert(doc("o"))
        dao.moveToRecycleBin(young, now - 60 * day); dao.moveToRecycleBin(old, now - 100 * day)
        dao.deleteOldRecycleBinItems(now - 90 * day)
        assertNotNull(dao.getById(young)); assertNull(dao.getById(old))
    }

    @Test fun rowPurge_neverTouchesTheFileOnDisk() = runBlocking {
        val f = java.io.File.createTempFile("original", ".pdf").apply { writeText("%PDF-1.4 original") }
        try {
            val id = dao.insert(doc("u").copy(filePath = f.absolutePath))
            dao.moveToRecycleBin(id, 1L)
            dao.deleteOldRecycleBinItems(Long.MAX_VALUE)
            assertNull(dao.getById(id))
            assertTrue(f.exists() && f.length() > 0)
        } finally { f.delete() }
    }
}
