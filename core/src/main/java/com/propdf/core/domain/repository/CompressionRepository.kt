package com.propdf.core.domain.repository

import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionPreview
import com.propdf.core.domain.model.CompressionResult
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository for PDF compression operations.
 * All operations are cancellable and report progress.
 */
interface CompressionRepository {
    
    /**
     * Execute full compression with progress reporting.
     * Emits Loading with progress (0.0-1.0) then Success or Error.
     */
    fun compress(
        sourceUri: String,
        outputUri: String,
        config: CompressionConfig
    ): Flow<AppResult<CompressionResult>>

    /**
     * Generate compression preview without writing output.
     * Fast estimation based on PDF structure analysis.
     */
    suspend fun preview(
        sourceUri: String,
        config: CompressionConfig
    ): AppResult<CompressionPreview>

    /**
     * Quick compress using predefined strategy.
     * Convenience method for simple use cases.
     */
    fun quickCompress(
        sourceUri: String,
        outputUri: String,
        strategy: CompressionStrategy
    ): Flow<AppResult<CompressionResult>>
}
