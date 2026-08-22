package com.propdfeditor.ui.security

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Security Hub: password protect / AES encrypt / remove metadata are fully wired to
 * the real iText engines in :security (see SecurityViewModel). Redact and Verify
 * Signature don't yet have an interactive Compose UI (redaction needs a page-rect
 * marking tool; verification needs a results screen) so they're honestly disabled
 * with a "coming soon" message rather than left as silent no-op buttons. Digital
 * Sign routes to the existing annotation flow's Signature tool, which is the app's
 * one actually-working way to place a signature on a page today.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityHubScreen(
    documentUri: String,
    onNavigateBack: () -> Unit,
    onNavigateToSign: (String) -> Unit = {},
    viewModel: SecurityViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var passwordDialogAction by remember { mutableStateOf<PendingAction?>(null) }
    var pendingOutputUri by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(documentUri) {
        viewModel.loadDocument(documentUri)
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { outputUri: Uri? ->
        if (outputUri == null) {
            viewModel.cancelPendingAction()
        } else if (uiState.pendingAction == PendingAction.REMOVE_METADATA) {
            viewModel.onOutputLocationChosen(outputUri)
        } else {
            // Password-based actions need a password first; stash the output
            // location and open the password dialog.
            pendingOutputUri = outputUri
            passwordDialogAction = uiState.pendingAction
        }
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(tools, key = { it.name }) { tool ->
                    SecurityToolCard(
                        tool = tool,
                        enabled = !uiState.isProcessing,
                        onClick = {
                            when (tool.action) {
                                SecurityAction.PASSWORD -> {
                                    viewModel.requestPasswordProtect()
                                    saveDocumentLauncher.launch("protected_document.pdf")
                                }
                                SecurityAction.ENCRYPT -> {
                                    viewModel.requestAesEncrypt()
                                    saveDocumentLauncher.launch("encrypted_document.pdf")
                                }
                                SecurityAction.REDACT -> viewModel.showComingSoon("Redaction")
                                SecurityAction.SIGN -> onNavigateToSign(documentUri)
                                SecurityAction.VERIFY -> viewModel.showComingSoon("Signature verification")
                                SecurityAction.METADATA -> {
                                    viewModel.requestRemoveMetadata()
                                    saveDocumentLauncher.launch("cleaned_document.pdf")
                                }
                            }
                        }
                    )
                }
            }

            if (uiState.isProcessing) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }

    passwordDialogAction?.let { action ->
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                passwordDialogAction = null
                pendingOutputUri = null
                viewModel.cancelPendingAction()
            },
            title = { Text(if (action == PendingAction.AES_ENCRYPT) "Set AES-256 password" else "Set password") },
            text = {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val outputUri = pendingOutputUri
                        if (outputUri != null && password.isNotBlank()) {
                            viewModel.onOutputLocationChosen(outputUri, password)
                        }
                        passwordDialogAction = null
                        pendingOutputUri = null
                    },
                    enabled = password.isNotBlank()
                ) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = {
                    passwordDialogAction = null
                    pendingOutputUri = null
                    viewModel.cancelPendingAction()
                }) { Text("Cancel") }
            }
        )
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
    enabled: Boolean,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        enabled = enabled,
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
