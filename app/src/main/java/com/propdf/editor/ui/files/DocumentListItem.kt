package com.propdf.editor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.propdf.editor.domain.model.DocumentCategory
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.utils.formatFileSize
import com.propdf.editor.ui.theme.*

@Composable
fun DocumentListItem(
    document: PdfDocument,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onDeleteClick: (() -> Unit)? = null,
    onRestoreClick: (() -> Unit)? = null
) {
    val color = when {
        document.isDeleted -> pdf_red
        document.isFavorite -> pdf_amber
        else -> pdf_blue
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (document.isDeleted) Icons.Default.Delete else Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatFileSize(document.fileSize)} · ${document.category.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (document.cloudProvider != null) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = "Cloud",
                    tint = pdf_teal,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            if (onRestoreClick != null && document.isDeleted) {
                IconButton(onClick = onRestoreClick) {
                    Icon(Icons.Default.Restore, contentDescription = "Restore", tint = pdf_green)
                }
            } else {
                IconButton(onClick = onFavoriteClick) {
                    Icon(
                        imageVector = if (document.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Favorite",
                        tint = if (document.isFavorite) pdf_amber else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onDeleteClick != null) {
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = if (document.isDeleted) Icons.Default.DeleteForever else Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = pdf_red
                    )
                }
            }
        }
    }
}
