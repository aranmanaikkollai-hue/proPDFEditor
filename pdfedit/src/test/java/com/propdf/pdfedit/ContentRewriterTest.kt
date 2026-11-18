package com.propdf.pdfedit

import com.propdf.pdfedit.content.ContentRewriter
import com.propdf.pdfedit.content.ContentScanner
import com.propdf.pdfedit.content.Instr
import com.propdf.pdfedit.content.Operand
import com.propdf.pdfedit.content.SiteAction
import com.propdf.pdfedit.geometry.Matrix2D
import com.propdf.pdfedit.geometry.PointPds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentRewriterTest {
    private val eps = 1e-9
    private val isImage: (String) -> Boolean = { it.startsWith("Im") }

    private fun cm(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) =
        Instr("cm", listOf(a, b, c, d, e, f).map { Operand.Num(it) })

    private fun doOp(name: String) = Instr("Do", listOf(Operand.Name(name)))
    private fun q() = Instr("q", emptyList())
    private fun bigQ() = Instr("Q", emptyList())

    private fun assertMatrix(expected: Matrix2D, actual: Matrix2D) {
        assertEquals(expected.a, actual.a, 1e-6); assertEquals(expected.b, actual.b, 1e-6)
        assertEquals(expected.c, actual.c, 1e-6); assertEquals(expected.d, actual.d, 1e-6)
        assertEquals(expected.e, actual.e, 1e-6); assertEquals(expected.f, actual.f, 1e-6)
    }

    @Test
    fun scannerTracksTheMatrixThroughQAndCm() {
        val stream = listOf(
            q(), cm(2.0, 0.0, 0.0, 2.0, 10.0, 20.0),
            q(), cm(1.0, 0.0, 0.0, 1.0, 5.0, 5.0), doOp("Im0"), bigQ(),
            doOp("Im1"), bigQ()
        )
        val sites = ContentScanner.imageSites(stream, isImage)
        assertEquals(2, sites.size)
        assertMatrix(Matrix2D(2.0, 0.0, 0.0, 2.0, 20.0, 30.0), sites[0].ctm) // inner translate, then outer scale
        assertMatrix(Matrix2D(2.0, 0.0, 0.0, 2.0, 10.0, 20.0), sites[1].ctm) // inner state was popped
        assertEquals(listOf(0, 1), sites.map { it.imageOrdinal })
    }

    @Test
    fun nonImageXObjectsAreIgnoredAndDoNotConsumeOrdinals() {
        val stream = listOf(doOp("Fm0"), doOp("Im0"), doOp("Fm1"), doOp("Im1"))
        val sites = ContentScanner.imageSites(stream, isImage)
        assertEquals(listOf("Im0", "Im1"), sites.map { it.xObjectName })
        assertEquals(listOf(0, 1), sites.map { it.imageOrdinal })
    }

    @Test
    fun unbalancedQDoesNotCrash() {
        val sites = ContentScanner.imageSites(listOf(bigQ(), bigQ(), doOp("Im0")), isImage)
        assertEquals(1, sites.size)
    }

    @Test
    fun malformedCmIsIgnored() {
        val bad = Instr("cm", listOf(Operand.Num(1.0), Operand.Num(2.0)))
        val sites = ContentScanner.imageSites(listOf(bad, doOp("Im0")), isImage)
        assertMatrix(Matrix2D.IDENTITY, sites[0].ctm)
    }

    @Test
    fun deleteDropsOnlyThatDrawing() {
        val keepA = Instr("BT", emptyList(), source = "a")
        val keepB = Instr("ET", emptyList(), source = "b")
        val stream = listOf(keepA, doOp("Im0"), doOp("Im1"), keepB)
        val result = ContentRewriter.rewrite(stream, isImage, mapOf(0 to SiteAction.Delete))
        assertEquals(1, result.applied)
        assertEquals(listOf("BT", "Do", "ET"), result.instrs.map { it.op })
        assertEquals("Im1", (result.instrs[1].operands.last() as Operand.Name).name)
        assertSame(keepA, result.instrs[0])
        assertSame(keepB, result.instrs[2])
    }

    @Test
    fun placeWrapsTheDrawingSoItLandsExactlyOnTheRequestedMatrix() {
        val ctm = cm(2.0, 0.0, 0.0, 3.0, 10.0, 20.0)
        val stream = listOf(q(), ctm, doOp("Im0"), bigQ())
        val wanted = Matrix2D(100.0, 0.0, 0.0, 50.0, 30.0, 40.0)
        val result = ContentRewriter.rewrite(stream, isImage, mapOf(0 to SiteAction.Place(wanted)))
        assertEquals(listOf("q", "cm", "q", "cm", "Do", "Q", "Q"), result.instrs.map { it.op })
        assertEquals(1, result.applied)

        // Re-scan the rewritten stream: the image must now be drawn with exactly the wanted matrix.
        val sites = ContentScanner.imageSites(result.instrs, isImage)
        assertEquals(1, sites.size)
        assertMatrix(wanted, sites[0].ctm)
    }

    @Test
    fun placeWorksUnderARotatedAndNestedMatrix() {
        val stream = listOf(
            q(), cm(0.0, 1.0, -1.0, 0.0, 612.0, 0.0),
            q(), cm(0.5, 0.0, 0.0, 0.5, 7.0, 9.0), doOp("Im0"), bigQ(), bigQ()
        )
        val wanted = Matrix2D(80.0, 10.0, -10.0, 60.0, 15.0, 25.0)
        val result = ContentRewriter.rewrite(stream, isImage, mapOf(0 to SiteAction.Place(wanted)))
        assertMatrix(wanted, ContentScanner.imageSites(result.instrs, isImage)[0].ctm)
        // A point of the unit square ends up where the wanted matrix puts it.
        val p = ContentScanner.imageSites(result.instrs, isImage)[0].ctm.transform(PointPds(1.0, 1.0))
        val q = wanted.transform(PointPds(1.0, 1.0))
        assertEquals(q.x, p.x, 1e-6); assertEquals(q.y, p.y, 1e-6)
    }

    @Test
    fun singularMatrixIsSkippedAndReported() {
        val stream = listOf(cm(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), doOp("Im0"))
        val result = ContentRewriter.rewrite(stream, isImage, mapOf(0 to SiteAction.Place(Matrix2D.IDENTITY)))
        assertEquals(0, result.applied)
        assertEquals(listOf(0), result.skippedOrdinals)
        assertEquals(listOf("cm", "Do"), result.instrs.map { it.op })
    }

    @Test
    fun actionsForUnknownOrdinalsChangeNothing() {
        val stream = listOf(doOp("Im0"))
        val result = ContentRewriter.rewrite(stream, isImage, mapOf(5 to SiteAction.Delete))
        assertEquals(0, result.applied)
        assertSame(stream[0], result.instrs[0])
    }

    @Test
    fun noActionsReturnTheSameInstructions() {
        val stream = listOf(q(), doOp("Im0"), bigQ())
        val result = ContentRewriter.rewrite(stream, isImage, emptyMap())
        assertEquals(stream.size, result.instrs.size)
        stream.indices.forEach { assertSame(stream[it], result.instrs[it]) }
        assertTrue(result.skippedOrdinals.isEmpty())
    }
}
