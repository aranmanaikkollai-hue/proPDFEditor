package com.propdf.scanner.data.local

import android.graphics.PointF
import com.propdf.scanner.model.*

fun ScannedDocument.toEntity() = ScannedDocumentEntity(id, name, scanMode, createdAt, updatedAt)
fun ScannedDocumentEntity.toModel(pages: List<ScannedPage>) = ScannedDocument(id, name, pages, scanMode, createdAt, updatedAt)

fun ScannedPage.toEntity(documentId: String): ScannedPageEntity {
    val edge = documentEdge
    return ScannedPageEntity(
        id, documentId, originalImagePath, processedImagePath,
        edge?.topLeft?.x ?: 0f, edge?.topLeft?.y ?: 0f,
        edge?.topRight?.x ?: 0f, edge?.topRight?.y ?: 0f,
        edge?.bottomRight?.x ?: 0f, edge?.bottomRight?.y ?: 0f,
        edge?.bottomLeft?.x ?: 0f, edge?.bottomLeft?.y ?: 0f,
        colorFilter, rotation, timestamp, pageNumber
    )
}

fun ScannedPageEntity.toModel(): ScannedPage {
    val edge = if (edgeTopLeftX != 0f || edgeTopLeftY != 0f || edgeTopRightX != 0f || edgeTopRightY != 0f ||
        edgeBottomRightX != 0f || edgeBottomRightY != 0f || edgeBottomLeftX != 0f || edgeBottomLeftY != 0f) {
        DocumentEdge(
            PointF(edgeTopLeftX, edgeTopLeftY), PointF(edgeTopRightX, edgeTopRightY),
            PointF(edgeBottomRightX, edgeBottomRightY), PointF(edgeBottomLeftX, edgeBottomLeftY)
        )
    } else null

    return ScannedPage(id, originalImagePath, processedImagePath, edge, colorFilter, rotation, timestamp, pageNumber)
}
