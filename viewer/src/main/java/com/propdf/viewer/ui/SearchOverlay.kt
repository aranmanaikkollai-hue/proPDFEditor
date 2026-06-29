package com.propdf.viewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.propdf.viewer.model.SearchResult

/**
 * Production search overlay with next/previous navigation,
 * result highlighting, and match count display.
 */
@Composable
fun SearchOverlayV2(
    searchResults: List<SearchResult>,
    currentResultIndex: Int,
    searchQuery: String,
    isSearching: Boolean,
    onSearch: (String) -> Unit,
    onNextResult: () -> Unit,
    onPreviousResult: () -> Unit,
    onResultSelected: (SearchResult) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(searchQuery) }
    var showResults by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Search input row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        if (it.length >= 2) {
                            onSearch(it)
                            showResults = true
                        } else if (it.isEmpty()) {
                            showResults = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search in document...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = {
                                query = ""
                                showResults = false
                            }) {
                                Icon(Icons.Default.Close, "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            onSearch(query)
                            showResults = true
                        }
                    ),
                    singleLine = true,
                    shape = MaterialTheme.shapes.large
                )

                // Navigation buttons
                if (searchResults.isNotEmpty()) {
                    IconButton(onClick = onPreviousResult, enabled = currentResultIndex > 0) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Previous")
                    }
                    Text(
                        text = "${currentResultIndex + 1}/${searchResults.size}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    IconButton(
                        onClick = onNextResult,
                        enabled = currentResultIndex < searchResults.size - 1
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Next")
                    }
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "Close search")
                }
            }

            // Progress indicator
            AnimatedVisibility(visible = isSearching) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }

            // Results count
            if (query.length >= 2 && !isSearching) {
                Text(
                    text = if (searchResults.isEmpty()) "No results found"
                           else "${searchResults.size} results found",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            // Results list
            AnimatedVisibility(
                visible = showResults && searchResults.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                LazyColumn(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .animateContentSize()
                        .padding(top = 8.dp)
                ) {
                    itemsIndexed(searchResults) { index, result ->
                        SearchResultItemV2(
                            result = result,
                            query = query,
                            isCurrentResult = index == currentResultIndex,
                            onClick = { onResultSelected(result) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultItemV2(
    result: SearchResult,
    query: String,
    isCurrentResult: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isCurrentResult) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        shape = MaterialTheme.shapes.medium,
        color = backgroundColor
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Page ${result.pageIndex + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (result.matchCount > 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${result.matchCount} matches",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            val highlighted = buildHighlightedText(result.textSnippet, query)
            Text(
                text = highlighted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2
            )
        }
    }
}

@Composable
private fun buildHighlightedText(text: String, query: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        if (query.isBlank()) {
            append(text)
            return@buildAnnotatedString
        }

        val lowerText = text.lowercase()
        val lowerQuery = query.lowercase()
        var currentIndex = 0

        while (currentIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, currentIndex)
            if (matchIndex == -1) {
                append(text.substring(currentIndex))
                break
            }

            append(text.substring(currentIndex, matchIndex))

            withStyle(
                style = SpanStyle(
                    background = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            ) {
                append(text.substring(matchIndex, matchIndex + query.length))
            }

            currentIndex = matchIndex + query.length
        }
    }
}

/**
 * Floating page indicator showing current page / total pages.
 * Auto-hides after inactivity.
 */
@Composable
fun FloatingPageIndicator(
    currentPage: Int,
    totalPages: Int,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(currentPage) {
        visible = true
        kotlinx.coroutines.delay(2000)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
            tonalElevation = 4.dp,
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "${currentPage + 1} / $totalPages",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Page jump dialog for quick navigation.
 */
@Composable
fun PageJumpDialog(
    currentPage: Int,
    totalPages: Int,
    onPageSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var pageInput by remember { mutableStateOf((currentPage + 1).toString()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .widthIn(min = 280.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Go to Page",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = pageInput,
                    onValueChange = {
                        pageInput = it.filter { c -> c.isDigit() }
                    },
                    label = { Text("Page (1-$totalPages)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val page = pageInput.toIntOrNull()?.minus(1)?.coerceIn(0, totalPages - 1)
                            page?.let { onPageSelected(it); onDismiss() }
                        }
                    )
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val page = pageInput.toIntOrNull()?.minus(1)?.coerceIn(0, totalPages - 1)
                            page?.let { onPageSelected(it); onDismiss() }
                        }
                    ) {
                        Text("Go")
                    }
                }
            }
        }
    }
}
