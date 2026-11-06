package com.propdf.editor.data.repository

import android.content.Context
import com.propdf.core.data.entity.PdfDocumentEntity
import com.propdf.core.data.local.dao.DocumentCollectionDao
import com.propdf.core.data.local.dao.DocumentTagDao
import com.propdf.core.data.local.dao.PdfDocumentDao
import com.propdf.core.data.local.dao.RecentActivityDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertTrue

/** Drives the production DocumentRepositoryImpl.cleanOldRecycleBinItems against mocked DAOs (no real database). */
class RecycleBinCleanupRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()
    private val day = 86_400_000L
    private val dao = mockk<PdfDocumentDao>(relaxed = true)
    private val repo: DocumentRepositoryImpl

    init {
        val ctx = mockk<Context>(relaxed = true)
        repo = DocumentRepositoryImpl(ctx, dao, mockk<DocumentTagDao>(relaxed = true),
            mockk<DocumentCollectionDao>(relaxed = true), mockk<RecentActivityDao>(relaxed = true))
    }

    private fun bin(id: Long, deletedAt: Long?) = PdfDocumentEntity(
        id = id, uriString = "content://x/$id", fileName = "f$id.pdf", filePath = "/nonexistent/f$id.pdf",
        sizeBytes = 1, lastModified = 1, isInRecycleBin = true, deletedAt = deletedAt
    )

    private fun setBin(vararg e: PdfDocumentEntity) {
        every { dao.getRecycleBinDocuments() } returns flowOf(e.toList())
        e.forEach { coEvery { dao.getById(it.id) } returns it }
    }

    @Test fun thirtyDayCleanup_keepsRecentItems_andDeletesOldOnes() = runBlocking {
        val now = System.currentTimeMillis()
        setBin(bin(1, now - 10 * day), bin(2, now - 40 * day))
        repo.cleanOldRecycleBinItems(30)
        coVerify(exactly = 0) { dao.deletePermanently(1) }
        coVerify(exactly = 1) { dao.deletePermanently(2) }
    }

    @Test fun ninetyDayCleanup_doesNotOverflow_andKeepsEverythingRecent() = runBlocking {
        val now = System.currentTimeMillis()
        setBin(bin(1, now - 1 * day), bin(2, now - 60 * day))
        repo.cleanOldRecycleBinItems(90)
        coVerify(exactly = 0) { dao.deletePermanently(any()) }
    }

    @Test fun itemWithoutDeletedAt_isNotPurgedByAge() = runBlocking {
        setBin(bin(1, null))
        repo.cleanOldRecycleBinItems(30)
        coVerify(exactly = 0) { dao.deletePermanently(any()) }
    }

    @Test fun zeroDays_emptiesTheWholeBin() = runBlocking {
        val now = System.currentTimeMillis()
        setBin(bin(1, now), bin(2, null))
        repo.cleanOldRecycleBinItems(0)
        coVerify(exactly = 1) { dao.deletePermanently(1) }
        coVerify(exactly = 1) { dao.deletePermanently(2) }
    }

    @Test fun repeatedCleanup_isIdempotent() = runBlocking {
        val now = System.currentTimeMillis()
        setBin(bin(2, now - 40 * day))
        repo.cleanOldRecycleBinItems(30)
        every { dao.getRecycleBinDocuments() } returns flowOf(emptyList())
        repo.cleanOldRecycleBinItems(30)
        coVerify(exactly = 1) { dao.deletePermanently(2) }
    }

    @Test fun daoError_propagates_andNothingIsDeleted() {
        every { dao.getRecycleBinDocuments() } throws IllegalStateException("db")
        var thrown = false
        try { runBlocking { repo.cleanOldRecycleBinItems(30) } } catch (e: IllegalStateException) { thrown = true }
        assert(thrown)
        coVerify(exactly = 0) { dao.deletePermanently(any()) }
    }

    @Test fun oneDayCleanup_purgesItemsOlderThanOneDay_keepsNewer() = runBlocking {
        val now = System.currentTimeMillis()
        setBin(bin(1, now - 2 * day), bin(2, now - day / 2))
        repo.cleanOldRecycleBinItems(1)
        coVerify(exactly = 1) { dao.deletePermanently(1) }
        coVerify(exactly = 0) { dao.deletePermanently(2) }
    }

    @Test fun twentyNineDayOldItem_isRetainedBy30DaySetting() = runBlocking {
        val now = System.currentTimeMillis()
        setBin(bin(1, now - 29 * day))
        repo.cleanOldRecycleBinItems(30)
        coVerify(exactly = 0) { dao.deletePermanently(any()) }
    }

    private fun binWithRealFile(id: Long, deletedAt: Long?): Pair<PdfDocumentEntity, java.io.File> {
        val f = tmp.newFile("original$id.pdf").apply { writeText("%PDF-1.4 original user document") }
        return bin(id, deletedAt).copy(filePath = f.absolutePath) to f
    }

    @Test fun autoCleanup_neverDeletesTheOriginalFile() = runBlocking {
        val (e, f) = binWithRealFile(1, System.currentTimeMillis() - 60 * day)
        setBin(e)
        repo.cleanOldRecycleBinItems(30)
        coVerify(exactly = 1) { dao.deletePermanently(1) }
        assertTrue("original file must survive", f.exists() && f.length() > 0)
    }

    @Test fun emptyBin_neverDeletesTheOriginalFiles() = runBlocking {
        val (a, fa) = binWithRealFile(1, System.currentTimeMillis())
        val (b, fb) = binWithRealFile(2, null)
        setBin(a, b)
        repo.emptyRecycleBin()
        coVerify(exactly = 1) { dao.deletePermanently(1) }
        coVerify(exactly = 1) { dao.deletePermanently(2) }
        assertTrue(fa.exists() && fb.exists())
    }

    @Test fun perItemPermanentDelete_removesRowOnly() = runBlocking {
        val (e, f) = binWithRealFile(7, System.currentTimeMillis())
        setBin(e)
        repo.permanentlyDelete(7)
        coVerify(exactly = 1) { dao.deletePermanently(7) }
        assertTrue(f.exists())
    }

    @Test fun permanentDelete_ofMissingRow_isANoOp() = runBlocking {
        coEvery { dao.getById(99) } returns null
        repo.permanentlyDelete(99)
        coVerify(exactly = 0) { dao.deletePermanently(any()) }
    }
}
