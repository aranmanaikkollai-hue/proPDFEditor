package com.propdf.editor.ui.files

import androidx.compose.runtime.Composable
import com.propdf.core.domain.model.PdfDocument

@Composable
fun FilesScreen(
    onOpenPdf: () -> Unit,
    onOpenDocument: (PdfDocument) -> Unit
) {
    // Delegate to unified DocumentManagerScreen
    DocumentManagerScreen(onOpenDocument = onOpenDocument)
}
