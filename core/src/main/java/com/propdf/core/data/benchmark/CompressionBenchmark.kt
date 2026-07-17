package com.propdf.core.data.benchmark

import android.content.Context
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionResult
import com.propdf.core.domain.model.QualityPreset
import com.propdf.core.domain.repository.CompressionRepository
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CompressionBenchmark @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: CompressionRepository
) {
    suspend fun benchmark(
        sourceUri: String,
        presets: List<QualityPreset> = QualityPreset.entries
    ): List<BenchmarkResult> {
        val results = mutableListOf<BenchmarkResult>()
        val tempDir = File(context.cacheDir, "benchmark")
        tempDir.mkdirs()
        
        presets.forEach { preset ->
            val outputFile = File(tempDir, "benchmark_${preset.name}.pdf")
            val outputUri = outputFile.toURI().toString()
            
            try {
                var compressionResult: CompressionResult? = null
                val startTime = System.currentTimeMillis()
                
                repository.compress(sourceUri, outputUri, preset.config).collect { result ->
                    when (result) {
                        is AppResult.Success -> compressionResult = result.data
                        else -> {}
                    }
                }
                
                compressionResult?.let {
                    results.add(
                        BenchmarkResult(
                            preset = preset,
                            result = it,
                            throughputKbps = (it.originalSizeBytes / 1024.0) / 
                                ((System.currentTimeMillis() - startTime) / 1000.0)
                        )
                    )
                }
            } catch (e: Exception) {
                // Skip failed benchmarks
            } finally {
                outputFile.delete()
            }
        }
        
        return results.sortedByDescending { it.result.compressionRatio }
    }
    
    data class BenchmarkResult(
        val preset: QualityPreset,
        val result: CompressionResult,
        val throughputKbps: Double
    )
}
