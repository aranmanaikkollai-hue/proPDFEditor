package com.propdf.viewer.search

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.propdf.core.data.database.SearchDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for SearchIndex using Robolectric.
 * Tests FTS4 search functionality without requiring a real device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchIndexTest {

    private lateinit var database: SearchDatabase
    private lateinit var searchIndex: SearchIndex

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, SearchDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        searchIndex = SearchIndex(context, database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testDocumentIndexing() = runBlocking {
        val pageTexts = mapOf(
            0 to "This is the first page of the document.",
            1 to "The second page contains more text.",
            2 to "Page three has different content."
        )

        searchIndex.indexDocument("doc_123", pageTexts)
        val results = searchIndex.search("doc_123", "page")

        assertTrue(results.isNotEmpty())
        assertTrue(results.any { it.pageIndex == 0 })
        assertTrue(results.any { it.pageIndex == 1 })
    }

    @Test
    fun testCaseInsensitiveSearch() = runBlocking {
        val pageTexts = mapOf(0 to "Hello World PDF Document")
        searchIndex.indexDocument("doc_456", pageTexts)

        val resultsLower = searchIndex.search("doc_456", "hello")
        val resultsUpper = searchIndex.search("doc_456", "HELLO")

        assertEquals(1, resultsLower.size)
        assertEquals(1, resultsUpper.size)
    }

    @Test
    fun testRecentSearches() = runBlocking {
        val pageTexts = mapOf(0 to "Test document content")
        searchIndex.indexDocument("doc_789", pageTexts)

        searchIndex.search("doc_789", "test")
        searchIndex.search("doc_789", "document")

        val recent = searchIndex.getRecentSearches("doc_789", 10)
        assertTrue(recent.contains("test"))
        assertTrue(recent.contains("document"))
    }

    @Test
    fun testClearDocumentIndex() = runBlocking {
        val pageTexts = mapOf(0 to "Content to be cleared")
        searchIndex.indexDocument("doc_clear", pageTexts)

        searchIndex.clearDocumentIndex("doc_clear")
        val results = searchIndex.search("doc_clear", "content")

        assertTrue(results.isEmpty())
    }

    @Test
    fun testSearchSnippets() = runBlocking {
        val longText = "The quick brown fox jumps over the lazy dog. " +
                "This is a test document with multiple sentences. " +
                "We want to verify that search snippets are extracted correctly."

        searchIndex.indexDocument("doc_snippet", mapOf(0 to longText))
        val results = searchIndex.search("doc_snippet", "verify")

        assertTrue(results.isNotEmpty())
        assertTrue(results[0].textSnippet.contains("verify"))
    }
}
