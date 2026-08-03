package com.propdfeditor.ui.home.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.propdf.core.domain.model.SmartSuggestion

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionsChips(
    suggestions: List<SmartSuggestion>,
    onSuggestionClick: (SmartSuggestion) -> Unit
) {
    Column {
        Text(
            "Suggestions",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.sortedBy { it.priority }.forEach { suggestion ->
                SuggestionChip(
                    onClick = { onSuggestionClick(suggestion) },
                    label = { Text(suggestion.title) },
                    icon = {
                        when (suggestion.action) {
                            "open" -> Text("📄")
                            "scan" -> Text("📷")
                            "compress" -> Text("🗜")
                            else -> Text("💡")
                        }
                    }
                )
            }
        }
    }
}
