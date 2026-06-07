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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

/**
 * Predictive preloading engine for PDF tiles.
 *
 * Strategy:
 * 1. Render visible tiles with highest priority
 * 2. Preload adjacent tiles (same page, nearby pages) with lower priority
 * 3. Cancel preload jobs when viewport changes significantly
 * 4. Memory-aware: limit preload buffer size based on device RAM
 * 5. Battery-aware: reduce preload aggressiveness on low battery
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

    /** Priority queue of tiles to preload: lower distance = higher priority */
    private val preloadQueue = PriorityBlockingQueue<PreloadTask>(100) { a, b ->
        a.priority.compareTo(b.priority)
    }

    /** Active preload jobs to allow cancellation */
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val jobsMutex = Mutex()

    private val _preloadStats = MutableStateFlow(PreloadStats())
    val preloadStats: StateFlow<PreloadStats> = _preloadStats.asStateFlow()

    private val preloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Whether preloading is enabled (can be disabled on low memory/battery) */
    private var isEnabled = true

    /**
     * Queue tiles for preloading based on current viewport and page grids.
     */
    suspend fun queuePreload(
        visibleTiles: List<Tile>,
        pageGrids: Map<Int, TileGrid>,
        currentPage: Int,
        totalPages: Int
    ) {
        if (!isEnabled) return

        // Cancel jobs for tiles that are no longer relevant
        val relevantTileIds = mutableSetOf<String>()

        // Add visible tiles (already being rendered by main pipeline, but track them)
        visibleTiles.forEach { relevantTileIds.add(it.id) }

        // Add adjacent tiles from current page
        val currentGrid = pageGrids[currentPage] ?: return
        val viewport = visibleTiles.firstOrNull()?.dstRect ?: Rect(0, 0, 100, 100)
        val adjacentTiles = currentGrid.getAdjacentTiles(viewport, PRELOAD_MARGIN_TILES)
        adjacentTiles.forEach { relevantTileIds.add(it.id) }

        // Add tiles from ahead pages
        for (page in (currentPage + 1)..minOf(currentPage + PRELOAD_AHEAD_PAGES, totalPages - 1)) {
            pageGrids[page]?.getVisibleTiles(viewport)?.let { tiles ->
                tiles.forEach { relevantTileIds.add(it.id) }
            }
        }

        // Add tiles from behind pages
        for (page in maxOf(0, currentPage - PRELOAD_BEHIND_PAGES) until currentPage) {
            pageGrids[page]?.getVisibleTiles(viewport)?.let { tiles ->
                tiles.forEach { relevantTileIds.add(it.id) }
            }
        }

        // Cancel irrelevant jobs
        jobsMutex.withLock {
            val toCancel = activeJobs.keys.filter { it !in relevantTileIds }
            toCancel.forEach { id ->
                activeJobs.remove(id)?.cancel()
            }
        }

        // Queue new preload tasks
        (adjacentTiles).forEach { tile ->
            if (!activeJobs.containsKey(tile.id) && tile.bitmapRef == null) {
                val task = PreloadTask(tile, tile.priority + 100) // Lower priority than visible
                preloadQueue.offer(task)
            }
        }

        // Start preload workers if not already running
        ensurePreloadWorkers()
    }

    /**
     * Set whether preloading is enabled. Disabled automatically on low memory.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            preloadScope.coroutineContext.cancel(CancellationException("Preloading disabled"))
        }
    }

    /**
     * Cancel all preload jobs and clear queue.
     */
    suspend fun cancelAll() {
        jobsMutex.withLock {
            activeJobs.values.forEach { it.cancel() }
            activeJobs.clear()
        }
        preloadQueue.clear()
    }

    private fun ensurePreloadWorkers() {
        // Limit concurrent preload jobs
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
                        // Expected on cancellation
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
