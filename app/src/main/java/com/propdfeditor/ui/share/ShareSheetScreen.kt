package com.propdfeditor.ui.share

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheetScreen(
    documentUri: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val shareOptions = listOf(
        ShareOption("Share PDF", Icons.Default.Share, "application/pdf"),
        ShareOption("Share as Images", Icons.Default.Image, "image/jpeg"),
        ShareOption("Print", Icons.Default.Print, "application/pdf"),
        ShareOption("Upload to Cloud", Icons.Default.CloudUpload, "application/pdf"),
        ShareOption("Send via Email", Icons.Default.Email, "application/pdf"),
        ShareOption("Nearby Share", Icons.Default.NearMe, "application/pdf")
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Share") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Share Document",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                documentUri.substringAfterLast("/"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            shareOptions.forEach { option ->
                ListItem(
                    headlineContent = { Text(option.label) },
                    leadingContent = {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    modifier = Modifier.clickable {
                        val uri = Uri.parse(documentUri)
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = option.mimeType
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(shareIntent, "Share via")
                        )
                    }
                )
            }
        }
    }
}

private data class ShareOption(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val mimeType: String
)
