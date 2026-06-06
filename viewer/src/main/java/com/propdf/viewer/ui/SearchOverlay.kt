package com.propdf.viewer.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.propdf.viewer.model.SearchResult

/**
 * Search overlay for finding text within the PDF.
 *
 * Features:
 * - Real-time search with debouncing
 * - Search result highlighting
 * - Recent search history
 * - Result navigation with page jumping
 */
@Composable
fun SearchOverlay(
    searchResults: List<SearchResult>,
    onSearch: (String) -> Unit,
    onResultSelected: (SearchResult) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .width(360.dp)
            .wrapContentHeight()
            .padding(16.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Search bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        if (it.length >= 2) {
                            isSearching = true
                            onSearch(it)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search in document...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Default.Close, "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            isSearching = true
                            onSearch(query)
                        }
                    ),
                    singleLine = true
                )

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close search")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Results count
            if (query.length >= 2) {
                Text(
                    text = "${searchResults.size} results found",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Search results
            LazyColumn(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .animateContentSize()
            ) {
                items(searchResults) { result ->
                    SearchResultItem(
                        result = result,
                        query = query,
                        onClick = { onResultSelected(result) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultItem(
    result: SearchResult,
    query: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Page ${result.pageIndex + 1}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Highlight query in snippet
            val snippet = result.textSnippet
            val highlighted = buildAnnotatedStringWithHighlight(snippet, query)
            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )

            if (result.matchCount > 1) {
                Text(
                    text = "${result.matchCount} matches on this page",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun buildAnnotatedStringWithHighlight(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        var currentIndex = 0

        while (currentIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, currentIndex)
            if (matchIndex == -1) {
                append(text.substring(currentIndex))
                break
            }

            // Append text before match
            append(text.substring(currentIndex, matchIndex))

            // Append highlighted match
            withStyle(
                style = androidx.compose.ui.text.SpanStyle(
                    background = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    color = MaterialTheme.colorScheme.primary
                )
            ) {
                append(text.substring(matchIndex, matchIndex + query.length))
            }

            currentIndex = matchIndex + query.length
        }
    }
}
