package com.propdf.editor.data.worker

import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFileInsertResult
import com.propdf.core.domain.repository.RecentFileLookup
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.SortOption
import com.propdf.editor.domain.repository.DocumentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The hourly scan must never overwrite an entry the user already has (favourite flag, category and
 * last-opened time are user data) and must never treat a failed lookup as "not found".
 * These tests drive the production class [DocumentScanRunner] (the logic DocumentScanWorker runs)
 * against a fake repository whose insertOrUpdateRecentFile has REPLACE semantics, so any call to it
 * would visibly destroy user data.
 */
class DocumentScanWorkerRuleTest {

    private class FakeSource(private val pdfs: List<RecentFile>, private val error: Exception? = null) : PdfDiscoverySource {
        override suspend fun forEachPdf(action: suspend (RecentFile) -> Unit) {
            pdfs.forEach { action(it) }
            error?.let { throw it }
        }
    }

    private class FakeRepo(initial: List<RecentFile> = emptyList()) : DocumentRepository {
        val rows = LinkedHashMap<String, RecentFile>().apply { initial.forEach { put(it.uri, it) } }
        var lookupOverride: (suspend (String) -> RecentFileLookup)? = null
        var insertOverride: ((RecentFile) -> RecentFileInsertResult)? = null
        var insertIfAbsentCalls = 0
        var replaceCalls = 0

        override suspend fun lookupRecentFile(uri: String): RecentFileLookup =
            lookupOverride?.invoke(uri) ?: rows[uri]?.let { RecentFileLookup.Found(it) } ?: RecentFileLookup.NotFound

        override suspend fun insertRecentFileIfAbsent(recentFile: RecentFile): RecentFileInsertResult {
            insertIfAbsentCalls++
            insertOverride?.let { return it(recentFile) }
            if (rows.containsKey(recentFile.uri)) return RecentFileInsertResult.AlreadyExists
            rows[recentFile.uri] = recentFile
            return RecentFileInsertResult.Inserted
        }

        override suspend fun insertOrUpdateRecentFile(recentFile: RecentFile) {
            replaceCalls++
            rows[recentFile.uri] = recentFile // REPLACE semantics
        }

        override suspend fun getAllDocuments(): List<PdfDocument> = emptyList()
        override fun getRecentFiles(): Flow<List<PdfDocument>> = emptyFlow()
        override fun getFavorites(): Flow<List<PdfDocument>> = emptyFlow()
        override fun getDeletedFiles(): Flow<List<PdfDocument>> = emptyFlow()
        override fun searchFiles(query: String, sortBy: SortOption): Flow<List<PdfDocument>> = emptyFlow()
        override suspend fun setFavorite(id: Long, favorite: Boolean) {}
        override suspend fun deleteDocument(id: Long) {}
        override suspend fun restoreDocument(id: Long) {}
        override suspend fun permanentDelete(id: Long) {}
        override suspend fun emptyRecycleBin(olderThanDays: Int) {}
    }

    private fun discovered(uri: String) =
        RecentFile(uri = uri, name = "scan.pdf", size = 10, lastOpened = 111L, pageCount = 0, isFavorite = false, category = "")

    private fun userEdited(uri: String) =
        RecentFile(uri = uri, name = "Mine.pdf", size = 99, lastOpened = 5_000L, pageCount = 7, isFavorite = true, category = "Tax")

    private suspend fun scan(repo: FakeRepo, vararg pdfs: RecentFile) =
        DocumentScanRunner(FakeSource(pdfs.toList()), repo).run()

    @Test fun unknownPdf_isInserted() = runTest {
        val repo = FakeRepo()
        val o = scan(repo, discovered("u1"))
        assertEquals(1, o.inserted)
        assertEquals("u1", repo.rows["u1"]?.uri)
        assertEquals(ScanDecision.SUCCESS, DocumentScanRunner.decide(o, 0))
    }

    @Test fun knownPdf_isNotInserted() = runTest {
        val repo = FakeRepo(listOf(userEdited("u1")))
        val o = scan(repo, discovered("u1"))
        assertEquals(0, repo.insertIfAbsentCalls)
        assertEquals(0, repo.replaceCalls)
        assertEquals(1, o.alreadyKnown)
        assertEquals(0, o.inserted)
    }

    @Test fun existingFavourite_isUnchanged() = runTest {
        val repo = FakeRepo(listOf(userEdited("u1")))
        scan(repo, discovered("u1"))
        assertTrue(repo.rows.getValue("u1").isFavorite)
    }

    @Test fun existingCategory_isUnchanged() = runTest {
        val repo = FakeRepo(listOf(userEdited("u1")))
        scan(repo, discovered("u1"))
        assertEquals("Tax", repo.rows.getValue("u1").category)
    }

    @Test fun existingLastOpened_andMetadata_areUnchanged() = runTest {
        val repo = FakeRepo(listOf(userEdited("u1")))
        scan(repo, discovered("u1"))
        val row = repo.rows.getValue("u1")
        assertEquals(5_000L, row.lastOpened)
        assertEquals("Mine.pdf", row.name)
        assertEquals(99L, row.size)
        assertEquals(7, row.pageCount)
    }

    @Test fun lookupError_doesNotInsert_andIsReported() = runTest {
        val repo = FakeRepo()
        repo.lookupOverride = { RecentFileLookup.Failed(IllegalStateException("db"), retryable = false) }
        val o = scan(repo, discovered("u1"))
        assertEquals(0, repo.insertIfAbsentCalls)
        assertEquals(0, repo.replaceCalls)
        assertTrue(repo.rows.isEmpty())
        assertEquals(1, o.lookupFailures)
        assertNotNull(o.firstFailure)
        assertEquals(ScanDecision.FAILURE, DocumentScanRunner.decide(o, 0))
    }

    @Test fun lookupError_evenForRowThatExists_doesNotModifyIt() = runTest {
        val repo = FakeRepo(listOf(userEdited("u1")))
        repo.lookupOverride = { RecentFileLookup.Failed(IOException("busy"), retryable = true) }
        scan(repo, discovered("u1"))
        assertEquals(userEdited("u1"), repo.rows["u1"])
        assertEquals(0, repo.insertIfAbsentCalls)
    }

    @Test fun thrownRepositoryError_doesNotInsert() = runTest {
        val repo = FakeRepo()
        repo.lookupOverride = { throw RuntimeException("unexpected") }
        val o = scan(repo, discovered("u1"))
        assertEquals(0, repo.insertIfAbsentCalls)
        assertTrue(repo.rows.isEmpty())
        assertEquals(1, o.lookupFailures)
        assertEquals(ScanDecision.FAILURE, DocumentScanRunner.decide(o, 0))
    }

    @Test fun invalidFoundResult_forDifferentUri_doesNotInsert() = runTest {
        val repo = FakeRepo()
        repo.lookupOverride = { RecentFileLookup.Found(userEdited("some-other-uri")) }
        val o = scan(repo, discovered("u1"))
        assertEquals(0, repo.insertIfAbsentCalls)
        assertEquals(1, o.lookupFailures)
        assertFalse(repo.rows.containsKey("u1"))
    }

    @Test fun repeatedScanning_doesNotDuplicate_orOverwrite() = runTest {
        val repo = FakeRepo()
        scan(repo, discovered("u1"), discovered("u2"))
        // user edits u1 between scans
        repo.rows["u1"] = userEdited("u1")
        val second = scan(repo, discovered("u1"), discovered("u2"))
        assertEquals(2, repo.rows.size)
        assertEquals(0, second.inserted)
        assertEquals(2, second.alreadyKnown)
        assertEquals(userEdited("u1"), repo.rows["u1"])
        assertEquals(0, repo.replaceCalls)
    }

    @Test fun raceWhereRowAppearsAfterLookup_isNotOverwritten() = runTest {
        val repo = FakeRepo(listOf(userEdited("u1")))
        repo.lookupOverride = { RecentFileLookup.NotFound } // stale lookup; real insert is insert-if-absent
        val o = scan(repo, discovered("u1"))
        assertEquals(1, o.alreadyKnown)
        assertEquals(userEdited("u1"), repo.rows["u1"])
    }

    @Test fun oneFailingLookup_doesNotStopOtherDocuments() = runTest {
        val repo = FakeRepo()
        repo.lookupOverride = { uri ->
            if (uri == "bad") RecentFileLookup.Failed(IOException("x"), true) else RecentFileLookup.NotFound
        }
        val o = scan(repo, discovered("bad"), discovered("good"))
        assertTrue(repo.rows.containsKey("good"))
        assertFalse(repo.rows.containsKey("bad"))
        assertEquals(ScanDecision.RETRY, DocumentScanRunner.decide(o, 0))
    }

    @Test fun insertFailure_isReported_notSwallowed() = runTest {
        val repo = FakeRepo()
        repo.insertOverride = { RecentFileInsertResult.Failed(IllegalStateException("full"), retryable = false) }
        val o = scan(repo, discovered("u1"))
        assertEquals(1, o.insertFailures)
        assertEquals(ScanDecision.FAILURE, DocumentScanRunner.decide(o, 0))
    }

    @Test fun sourceFailure_isReported() = runTest {
        val repo = FakeRepo()
        val o = DocumentScanRunner(FakeSource(listOf(discovered("u1")), IllegalStateException("cursor")), repo).run()
        assertTrue(o.sourceFailed)
        assertEquals(ScanDecision.FAILURE, DocumentScanRunner.decide(o, 0))
    }

    @Test fun transientFailure_retriesThenStops() {
        val o = ScanOutcome(discovered = 1, lookupFailures = 1, hadTransientFailure = true, firstFailure = IOException())
        assertEquals(ScanDecision.RETRY, DocumentScanRunner.decide(o, 0))
        assertEquals(ScanDecision.RETRY, DocumentScanRunner.decide(o, 1))
        assertEquals(ScanDecision.FAILURE, DocumentScanRunner.decide(o, DocumentScanRunner.MAX_ATTEMPTS - 1))
    }

    @Test fun permanentFailure_neverRetries() {
        val o = ScanOutcome(lookupFailures = 1, hadPermanentFailure = true, hadTransientFailure = true)
        assertEquals(ScanDecision.FAILURE, DocumentScanRunner.decide(o, 0))
    }
}
