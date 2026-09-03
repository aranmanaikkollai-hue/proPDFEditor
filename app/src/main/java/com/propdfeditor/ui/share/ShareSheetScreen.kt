package com.propdfeditor.ui.share

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
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
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheetScreen(
    documentUri: String,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

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
                        try {
                            val parsed = Uri.parse(documentUri)
                            val shareUri = if (parsed.scheme == "file") {
                                FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    File(parsed.path ?: throw IllegalArgumentException("No path"))
                                )
                            } else {
                                parsed
                            }
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = option.mimeType
                                putExtra(Intent.EXTRA_STREAM, shareUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(
                                Intent.createChooser(shareIntent, "Share via")
                            )
                        } catch (e: Exception) {
                            // A raw file:// Uri passed to EXTRA_STREAM would otherwise crash
                            // with FileUriExposedException (targetSdk 34, API 24+) instead of
                            // showing a recoverable error.
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Couldn't share this file: ${e.message}")
                            }
                        }
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
