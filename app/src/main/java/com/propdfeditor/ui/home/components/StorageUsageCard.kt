package com.propdfeditor.ui.home.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DecimalFormat

@Composable
fun StorageUsageCard(
    usedBytes: Long,
    totalBytes: Long,
    recycleBinCount: Int
) {
    val progress = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
    val usedStr = formatBytes(usedBytes)
    val totalStr = formatBytes(totalBytes)

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Storage",
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "$usedStr / $totalStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (recycleBinCount > 0) {
                Spacer(modifier = Modifier.width(12.dp))
                BadgedBox(badge = { Badge { Text("$recycleBinCount") } }) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Recycle Bin"
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    if (bytes <= 0) return "0 B"
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val df = DecimalFormat("#,##0.##")
    return df.format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups.coerceAtMost(units.size - 1)]
}
