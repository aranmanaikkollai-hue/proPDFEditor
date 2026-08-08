package com.propdfeditor.ui.security

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityHubScreen(
    documentUri: String,
    onNavigateBack: () -> Unit,
    viewModel: SecurityViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(documentUri) {
        viewModel.loadDocument(documentUri)
    }

    val tools = listOf(
        SecurityTool("Password Protect", Icons.Default.Password, SecurityAction.PASSWORD),
        SecurityTool("AES Encrypt", Icons.Default.EnhancedEncryption, SecurityAction.ENCRYPT),
        SecurityTool("Redact", Icons.Default.FormatColorReset, SecurityAction.REDACT),
        SecurityTool("Digital Sign", Icons.Default.Draw, SecurityAction.SIGN),
        SecurityTool("Verify Sign", Icons.Default.Verified, SecurityAction.VERIFY),
        SecurityTool("Remove Metadata", Icons.Default.CleaningServices, SecurityAction.METADATA),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 140.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(tools, key = { it.name }) { tool ->
                SecurityToolCard(
                    tool = tool,
                    onClick = {
                        when (tool.action) {
                            SecurityAction.PASSWORD -> viewModel.setPassword("user_password")
                            SecurityAction.ENCRYPT -> viewModel.aesEncrypt()
                            SecurityAction.REDACT -> { /* Launch redaction UI */ }
                            SecurityAction.SIGN -> { /* Launch signature UI */ }
                            SecurityAction.VERIFY -> { /* Launch verification UI */ }
                            SecurityAction.METADATA -> viewModel.removeMetadata()
                        }
                    }
                )
            }
        }
    }

    uiState.message?.let { msg ->
        LaunchedEffect(msg) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearMessage()
        }
    }
}

@Composable
private fun SecurityToolCard(
    tool: SecurityTool,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.aspectRatio(1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = tool.name,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private data class SecurityTool(
    val name: String,
    val icon: ImageVector,
    val action: SecurityAction
)

private enum class SecurityAction {
    PASSWORD, ENCRYPT, REDACT, SIGN, VERIFY, METADATA
}
