package com.propdf.pdfedit.content

import com.propdf.pdfedit.geometry.Matrix2D

/** An operand of a content-stream instruction, reduced to what the scanner needs. */
sealed interface Operand {
    class Num(val value: Double) : Operand
    class Name(val name: String) : Operand
    object Other : Operand
}

/**
 * One content-stream instruction. [source] optionally carries the original parsed tokens so a rewriter can emit
 * untouched instructions exactly as they were (strings, arrays, dictionaries, inline image data).
 */
class Instr(val op: String, val operands: List<Operand>, val source: Any? = null)

/** A place where the stream draws an image XObject, with the matrix in force at that moment. */
class DoSite(val instrIndex: Int, val xObjectName: String, val imageOrdinal: Int, val ctm: Matrix2D)

/** What to do with one image drawing. */
sealed interface SiteAction {
    object Delete : SiteAction

    /** Draw the image with this user-space placement matrix (maps the unit square) instead. */
    class Place(val userMatrix: Matrix2D) : SiteAction
}

class RewriteResult(val instrs: List<Instr>, val applied: Int, val skippedOrdinals: List<Int>)

object ContentScanner {

    /**
     * Finds every Do that draws an image, tracking the current transformation matrix through q, Q and cm.
     * [isImage] says whether a named XObject is an image (forms are not followed).
     */
    fun imageSites(instrs: List<Instr>, isImage: (String) -> Boolean): List<DoSite> {
        val sites = ArrayList<DoSite>()
        val stack = ArrayList<Matrix2D>()
        var ctm = Matrix2D.IDENTITY
        var ordinal = 0
        for ((i, ins) in instrs.withIndex()) {
            when (ins.op) {
                "q" -> stack.add(ctm)
                "Q" -> if (stack.isNotEmpty()) ctm = stack.removeAt(stack.size - 1)
                "cm" -> {
                    val n = ins.operands.filterIsInstance<Operand.Num>()
                    if (n.size == 6) {
                        val m = Matrix2D(n[0].value, n[1].value, n[2].value, n[3].value, n[4].value, n[5].value)
                        ctm = m.then(ctm)
                    }
                }
                "Do" -> {
                    val name = (ins.operands.lastOrNull() as? Operand.Name)?.name
                    if (name != null && isImage(name)) {
                        sites.add(DoSite(i, name, ordinal++, ctm))
                    }
                }
            }
        }
        return sites
    }
}

object ContentRewriter {

    /**
     * Applies [actions] (keyed by image ordinal) to the stream. A Place action wraps the original Do in
     * q, X cm, Do, Q where X = placement x inverse(CTM), so the image lands exactly on the requested placement
     * whatever transformation was in force, and nothing else in the stream changes. A site whose CTM cannot be
     * inverted is left alone and reported in [RewriteResult.skippedOrdinals].
     */
    fun rewrite(instrs: List<Instr>, isImage: (String) -> Boolean, actions: Map<Int, SiteAction>): RewriteResult {
        val byIndex = ContentScanner.imageSites(instrs, isImage).associateBy { it.instrIndex }
        val out = ArrayList<Instr>(instrs.size + actions.size * 3)
        var applied = 0
        val skipped = ArrayList<Int>()
        for ((i, ins) in instrs.withIndex()) {
            val site = byIndex[i]
            val action = if (site != null) actions[site.imageOrdinal] else null
            if (site == null || action == null) {
                out.add(ins)
                continue
            }
            when (action) {
                is SiteAction.Delete -> applied++
                is SiteAction.Place -> {
                    val inverse = site.ctm.inverse()
                    if (inverse == null) {
                        skipped.add(site.imageOrdinal)
                        out.add(ins)
                    } else {
                        val x = action.userMatrix.then(inverse)
                        out.add(Instr("q", emptyList()))
                        out.add(Instr("cm", listOf(x.a, x.b, x.c, x.d, x.e, x.f).map { Operand.Num(it) }))
                        out.add(ins)
                        out.add(Instr("Q", emptyList()))
                        applied++
                    }
                }
            }
        }
        return RewriteResult(out, applied, skipped)
    }
}
