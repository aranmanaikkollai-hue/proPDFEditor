package com.propdf.editor.data.repository

import com.propdf.core.domain.model.ImageFitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

class PageGeometryTest {

    // Offset (non-origin) box: left=10, bottom=20, 200 x 300
    private fun geo(rotation: Int) = PageGeometry(10f, 20f, 210f, 320f, rotation)

    @Test fun rotationIsNormalised() {
        assertEquals(270, geo(-90).rotation)
        assertEquals(0, geo(360).rotation)
        assertEquals(90, geo(450).rotation)
    }

    @Test fun displaySizeSwapsOnQuarterTurns() {
        assertEquals(200f, geo(0).displayWidth, 0f)
        assertEquals(300f, geo(90).displayWidth, 0f)
        assertEquals(200f, geo(180).displayWidth, 0f)
        assertEquals(300f, geo(270).displayWidth, 0f)
    }

    @Test fun displayCornersMapOntoBoxCorners() {
        for (rot in listOf(0, 90, 180, 270)) {
            val g = geo(rot)
            val corners = setOf(0f, g.displayWidth).flatMap { x ->
                setOf(0f, g.displayHeight).map { y -> g.toUserX(x, y) to g.toUserY(x, y) }
            }.toSet()
            assertEquals(setOf(10f to 20f, 10f to 320f, 210f to 20f, 210f to 320f), corners)
        }
    }

    @Test fun displayAxesPointWhereRotationSaysTheyShould() {
        for (rot in listOf(0, 90, 180, 270)) {
            val g = geo(rot)
            val x0 = g.toUserX(0f, 0f); val y0 = g.toUserY(0f, 0f)
            val ax = Math.toDegrees(atan2((g.toUserY(1f, 0f) - y0).toDouble(), (g.toUserX(1f, 0f) - x0).toDouble()))
            assertEquals(((rot % 360) + 360) % 360, (((ax % 360) + 360) % 360).toInt())
            assertEquals(rot.toFloat() + 45f, g.userAngle(45f), 0f)
        }
    }

    @Test fun cropMarginsMapToTheSameDisplayedRectangle() {
        val dl = 7f; val dt = 11f; val dr = 13f; val db = 17f
        for (rot in listOf(0, 90, 180, 270)) {
            val g = geo(rot)
            val m = g.displayMarginsToUser(dl, dt, dr, db)
            val expectedUser = floatArrayOf(g.left + m.left, g.bottom + m.bottom, g.right - m.right, g.top - m.top)
            val xs = ArrayList<Float>(); val ys = ArrayList<Float>()
            for (x in listOf(dl, g.displayWidth - dr)) for (y in listOf(db, g.displayHeight - dt)) {
                xs.add(g.toUserX(x, y)); ys.add(g.toUserY(x, y))
            }
            assertEquals(expectedUser[0], xs.minOrNull()!!, 1e-4f)
            assertEquals(expectedUser[1], ys.minOrNull()!!, 1e-4f)
            assertEquals(expectedUser[2], xs.maxOrNull()!!, 1e-4f)
            assertEquals(expectedUser[3], ys.maxOrNull()!!, 1e-4f)
        }
    }

    @Test fun resizeKeepsContentCentredAndSwapsBoxOnQuarterTurns() {
        for (rot in listOf(0, 90, 180, 270)) {
            val g = geo(rot)
            val plan = g.planResize(400f, 200f, keepAspect = true, scaleContent = true)
            val quarter = rot == 90 || rot == 270
            assertEquals(if (quarter) 200f else 400f, plan.newWidth, 0f)
            assertEquals(if (quarter) 400f else 200f, plan.newHeight, 0f)
            val cx = (g.left + g.right) / 2f; val cy = (g.bottom + g.top) / 2f
            assertEquals(plan.newWidth / 2f, cx * plan.scaleX + plan.translateX, 1e-3f)
            assertEquals(plan.newHeight / 2f, cy * plan.scaleY + plan.translateY, 1e-3f)
        }
    }

    @Test fun resizeWithoutScalingKeepsUnitScale() {
        val plan = geo(0).planResize(500f, 500f, keepAspect = false, scaleContent = false)
        assertEquals(1f, plan.scaleX, 0f); assertEquals(1f, plan.scaleY, 0f)
    }

    @Test fun imageFitModes() {
        val (fw, fh) = ImageLayout.fit(ImageFitMode.FIT_CENTER, 1000f, 500f, 200f, 200f)
        assertEquals(200f, fw, 1e-3f); assertEquals(100f, fh, 1e-3f)
        assertEquals(200f to 200f, ImageLayout.fit(ImageFitMode.FILL, 1000f, 500f, 200f, 200f))
        assertEquals(1000f to 500f, ImageLayout.fit(ImageFitMode.ORIGINAL, 1000f, 500f, 200f, 200f))
        val (ww, wh) = ImageLayout.fit(ImageFitMode.FIT_WIDTH, 1000f, 500f, 200f, 200f)
        assertEquals(200f, ww, 1e-3f); assertEquals(100f, wh, 1e-3f)
        val (hw, hh) = ImageLayout.fit(ImageFitMode.FIT_HEIGHT, 1000f, 500f, 200f, 200f)
        assertEquals(400f, hw, 1e-3f); assertEquals(200f, hh, 1e-3f)
    }
}

class PageOrderPlannerTest {

    @Test fun deleteKeepsRemainingInOrderAndIgnoresInvalid() {
        assertEquals(listOf(1, 3, 5), PageOrderPlanner.deleteOrder(5, listOf(2, 4, 99, -1)))
    }

    @Test fun deletingEverythingYieldsEmpty() {
        assertTrue(PageOrderPlanner.deleteOrder(3, listOf(1, 2, 3)).isEmpty())
    }

    @Test fun duplicateInsertsOneCopyDirectlyAfterEachSelectedPage() {
        val order = PageOrderPlanner.duplicateOrder(4, listOf(2, 4))
        assertEquals(listOf(1, 2, 2, 3, 4, 4), order)
        assertEquals(4 + 2, order.size)
    }

    @Test fun moveUsesIndexIntoRemainingPages() {
        assertEquals(listOf(3, 1, 2, 4, 5), PageOrderPlanner.moveOrder(5, listOf(3), 0))
        assertEquals(listOf(1, 2, 4, 5, 3), PageOrderPlanner.moveOrder(5, listOf(3), 99))
        assertEquals(listOf(1, 2, 5, 3, 4), PageOrderPlanner.moveOrder(5, listOf(2, 5), 1))
    }

    @Test fun movedOrderIsAlwaysAPermutation() {
        val order = PageOrderPlanner.moveOrder(7, listOf(6, 2, 2, 99), 3)
        assertEquals((1..7).toList(), order.sorted())
    }

    @Test fun extractKeepsSelectionOrderAndDropsInvalid() {
        assertEquals(listOf(3, 1), PageOrderPlanner.extractOrder(4, listOf(3, 0, 1, 9)))
    }

    @Test fun insertAtClampsPosition() {
        assertEquals(listOf("x", "a", "b"), PageOrderPlanner.insertAt(listOf("a", "b"), -5, listOf("x")))
        assertEquals(listOf("a", "x", "b"), PageOrderPlanner.insertAt(listOf("a", "b"), 2, listOf("x")))
        assertEquals(listOf("a", "b", "x"), PageOrderPlanner.insertAt(listOf("a", "b"), 99, listOf("x")))
    }

    @Test fun coverageDecidesInPlaceVersusFreshDocument() {
        assertTrue(PageOrderPlanner.coversAllPages(3, listOf(3, 1, 2, 2)))
        assertTrue(!PageOrderPlanner.coversAllPages(3, listOf(1, 3)))
    }

    @Test fun splitChunksPartitionTheDocumentExactlyOnce() {
        for (n in 1..9) for (step in 1..10) {
            val flat = PageOrderPlanner.everyN(n, step).flatMap { it.toList() }
            assertEquals((1..n).toList(), flat)
        }
        assertEquals((1..10).toList(), PageOrderPlanner.bySize(10, 10_000_000L, 3_000_000L).flatMap { it.toList() })
        assertEquals((1..10).toList(), PageOrderPlanner.evenChunks(10, 3).flatMap { it.toList() })
        assertEquals((1..10).toList(), PageOrderPlanner.evenChunks(10, 0).flatMap { it.toList() })
    }
}
