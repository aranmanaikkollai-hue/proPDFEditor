package com.propdf.viewer.preload

import android.graphics.Rect
import com.propdf.viewer.model.Tile
import com.propdf.viewer.rendering.TileGrid
import com.propdf.viewer.rendering.TileRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

/**
 * Predictive preloading engine for PDF tiles.
 */
class PreloadManager(
    private val tileRenderer: TileRenderer,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val PRELOAD_MARGIN_TILES = 2
        private const val PRELOAD_AHEAD_PAGES = 2
        private const val PRELOAD_BEHIND_PAGES = 1
        private const val MAX_PRELOAD_JOBS = 6
    }

    private val preloadQueue = PriorityBlockingQueue<PreloadTask>(100) { a, b ->
        a.priority.compareTo(b.priority)
    }

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val jobsMutex = Mutex()

    private val _preloadStats = MutableStateFlow(PreloadStats())
    val preloadStats: StateFlow<PreloadStats> = _preloadStats.asStateFlow()

    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isEnabled = true

    suspend fun queuePreload(
        visibleTiles: List<Tile>,
        pageGrids: Map<Int, TileGrid>,
        currentPage: Int,
        totalPages: Int
    ) {
        if (!isEnabled) return

        val relevantTileIds = mutableSetOf<String>()
        visibleTiles.forEach { relevantTileIds.add(it.id) }

        val currentGrid = pageGrids[currentPage] ?: return
        val viewport = visibleTiles.firstOrNull()?.dstRect ?: Rect(0, 0, 100, 100)
        val adjacentTiles = currentGrid.getAdjacentTiles(viewport, PRELOAD_MARGIN_TILES)
        adjacentTiles.forEach { relevantTileIds.add(it.id) }

        for (page in (currentPage + 1)..minOf(currentPage + PRELOAD_AHEAD_PAGES, totalPages - 1)) {
            pageGrids[page]?.getVisibleTiles(viewport)?.let { tiles ->
                tiles.forEach { relevantTileIds.add(it.id) }
            }
        }

        for (page in maxOf(0, currentPage - PRELOAD_BEHIND_PAGES) until currentPage) {
            pageGrids[page]?.getVisibleTiles(viewport)?.let { tiles ->
                tiles.forEach { relevantTileIds.add(it.id) }
            }
        }

        jobsMutex.withLock {
            val toCancel = activeJobs.keys.filter { it !in relevantTileIds }
            toCancel.forEach { id ->
                activeJobs.remove(id)?.cancel()
            }
        }

        adjacentTiles.forEach { tile ->
            if (!activeJobs.containsKey(tile.id) && tile.bitmapRef == null) {
                val task = PreloadTask(tile, tile.priority + 100)
                preloadQueue.offer(task)
            }
        }

        ensurePreloadWorkers()
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            preloadScope.coroutineContext.cancel(CancellationException("Preloading disabled"))
        }
    }

    suspend fun cancelAll() {
        jobsMutex.withLock {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
        }
        preloadQueue.clear()
    }

    private fun ensurePreloadWorkers() {
        val currentJobs = activeJobs.size
        if (currentJobs >= MAX_PRELOAD_JOBS) return

        repeat(MAX_PRELOAD_JOBS - currentJobs) {
            preloadScope.launch {
                while (isActive) {
                    val task = preloadQueue.poll() ?: break
                    if (!isEnabled) break

                    jobsMutex.withLock {
                        if (activeJobs.containsKey(task.tile.id)) return@withLock
                        activeJobs[task.tile.id] = this.coroutineContext[Job]!!
                    }

                    try {
                        tileRenderer.renderTile(task.tile)
                        _preloadStats.update { it.copy(completedTiles = it.completedTiles + 1) }
                    } catch (e: CancellationException) {
                        // Expected
                    } finally {
                        jobsMutex.withLock {
                            activeJobs.remove(task.tile.id)
                        }
                    }
                }
            }
        }
    }

    data class PreloadTask(
        val tile: Tile,
        val priority: Int
    )

    data class PreloadStats(
        val queuedTiles: Int = 0,
        val activeJobs: Int = 0,
        val completedTiles: Int = 0,
        val cancelledTiles: Int = 0
    )
}
