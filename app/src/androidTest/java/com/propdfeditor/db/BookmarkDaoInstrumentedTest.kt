package com.propdfeditor.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.propdf.core.data.database.ProPDFDatabase
import com.propdf.core.data.entity.BookmarkEntity
import com.propdf.core.data.local.dao.BookmarkDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The viewer's bookmark storage: one row per (document, page), ordered by page, isolated per document. */
class BookmarkDaoInstrumentedTest {

    private lateinit var db: ProPDFDatabase
    private lateinit var dao: BookmarkDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ProPDFDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.bookmarkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun bookmark(uri: String, page: Int) = BookmarkEntity(uriString = uri, pageIndex = page)

    @Test
    fun pagesComeBackSortedAscending() = runBlocking {
        dao.addBookmark(bookmark(DOC_A, 7))
        dao.addBookmark(bookmark(DOC_A, 2))
        dao.addBookmark(bookmark(DOC_A, 5))
        assertEquals(listOf(2, 5, 7), dao.getBookmarkedPages(DOC_A))
    }

    @Test
    fun addingTheSamePageTwiceKeepsOneRow() = runBlocking {
        dao.addBookmark(bookmark(DOC_A, 3))
        dao.addBookmark(bookmark(DOC_A, 3))
        assertEquals(listOf(3), dao.getBookmarkedPages(DOC_A))
    }

    @Test
    fun removeDeletesOnlyThatPageOfThatDocument() = runBlocking {
        dao.addBookmark(bookmark(DOC_A, 1))
        dao.addBookmark(bookmark(DOC_A, 2))
        dao.addBookmark(bookmark(DOC_B, 1))
        dao.removeBookmark(DOC_A, 1)
        assertEquals(listOf(2), dao.getBookmarkedPages(DOC_A))
        assertEquals(listOf(1), dao.getBookmarkedPages(DOC_B))
    }

    @Test
    fun removingAMissingBookmarkIsHarmless() = runBlocking {
        dao.removeBookmark(DOC_A, 99)
        assertTrue(dao.getBookmarkedPages(DOC_A).isEmpty())
    }

    @Test
    fun clearRemovesAllBookmarksForOneDocument() = runBlocking {
        dao.addBookmark(bookmark(DOC_A, 1))
        dao.addBookmark(bookmark(DOC_A, 2))
        dao.addBookmark(bookmark(DOC_B, 4))
        dao.clearBookmarksForDocument(DOC_A)
        assertTrue(dao.getBookmarkedPages(DOC_A).isEmpty())
        assertEquals(listOf(4), dao.getBookmarkedPages(DOC_B))
    }

    @Test
    fun observeEmitsTheCurrentPages() = runBlocking {
        dao.addBookmark(bookmark(DOC_A, 6))
        assertEquals(listOf(6), dao.observeBookmarkedPages(DOC_A).first())
    }

    private companion object {
        const val DOC_A = "content://docs/a.pdf"
        const val DOC_B = "content://docs/b.pdf"
    }
}
