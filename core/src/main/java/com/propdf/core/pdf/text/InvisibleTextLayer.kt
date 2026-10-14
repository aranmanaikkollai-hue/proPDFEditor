package com.propdf.core.pdf.text

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * Phase 1: writes an invisible (text render mode 3) OCR text layer onto an existing PDF page with PDFBox, and
 * keeps non-Latin characters instead of silently dropping them.
 *
 * Fonts: Helvetica (WinAnsi) is used for every character it can encode. For other characters a script-matched
 * font is loaded from the device's system fonts (Noto, SIL OFL licence; only a subset of glyphs is embedded).
 * No font is bundled with the app. If no suitable font is installed the character is NOT written; it is counted
 * in [Stats.charactersDropped] so callers can report it instead of claiming full support.
 *
 * Invisible text needs no shaping: each code point is mapped to a glyph and carried through a ToUnicode map,
 * so extraction returns the original code points. Visual rendering of this text never happens (mode 3).
 * Only fonts loadable as a single TrueType/OpenType file are supported; .ttc collections (system CJK fonts)
 * are NOT, so CJK is reported as dropped. Arabic and Tamil extraction are NOT verified by tests that have run.
 */
object InvisibleTextLayer {

    /** One OCR line in bitmap pixel coordinates, origin top-left. */
    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

    data class Stats(val linesDrawn: Int, val charactersDropped: Int)

    /**
     * @param displayToUser affine [a b c d e f] mapping the page as displayed (origin bottom-left, size
     *        [displayW] x [displayH]) to user space; identity-with-box-origin for unrotated pages.
     */
    fun draw(
        document: PDDocument,
        page: PDPage,
        lines: List<Line>,
        bitmapWidth: Int,
        bitmapHeight: Int,
        displayW: Float,
        displayH: Float,
        displayToUser: FloatArray,
        resolver: UnicodeFontResolver = UnicodeFontResolver(document)
    ): Stats {
        if (bitmapWidth <= 0 || bitmapHeight <= 0 || lines.isEmpty()) return Stats(0, 0)
        val sx = displayW / bitmapWidth
        val sy = displayH / bitmapHeight
        var drawn = 0
        PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            cs.saveGraphicsState()
            val m = displayToUser
            cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
            cs.beginText()
            cs.setRenderingMode(RenderingMode.NEITHER)
            for (line in lines) {
                val runs = resolver.runsFor(line.text)
                if (runs.isEmpty()) continue
                val boxH = (line.bottom - line.top) * sy
                val boxW = (line.right - line.left) * sx
                if (boxH <= 0f || boxW <= 0f) continue
                val size = (boxH * 0.85f).coerceIn(1f, 200f)
                val natural = runs.sumOf { (it.font.getStringWidth(it.text) / 1000f * size).toDouble() }.toFloat()
                if (natural <= 0f) continue
                val hScale = (boxW / natural).coerceIn(0.05f, 20f)
                val x = line.left * sx
                val baseline = displayH - line.bottom * sy + boxH * 0.2f
                cs.setTextMatrix(Matrix(hScale, 0f, 0f, 1f, x, baseline))
                for (run in runs) {
                    cs.setFont(run.font, size)
                    cs.showText(run.text)
                }
                drawn++
            }
            cs.endText()
            cs.restoreGraphicsState()
        }
        return Stats(drawn, resolver.charactersDropped)
    }
}

/** Chooses, per character, a font able to encode it. See [InvisibleTextLayer] for the policy. */
class UnicodeFontResolver(
    private val document: PDDocument,
    private val fontDirs: List<File> = listOf(File("/system/fonts"), File("/product/fonts"), File("/system/product/fonts"))
) {
    data class Run(val font: PDFont, val text: String)

    var charactersDropped: Int = 0
        private set

    private val base: PDFont = PDType1Font.HELVETICA
    private val loaded = HashMap<String, PDFont?>()

    private class Candidate(val ranges: List<IntRange>, val fileNames: List<String>)

    private val candidates = listOf(
        Candidate(listOf(0x0B80..0x0BFF), listOf("NotoSansTamil-Regular.ttf", "NotoSansTamilUI-Regular.ttf")),
        Candidate(
            listOf(0x0600..0x06FF, 0x0750..0x077F, 0xFB50..0xFDFF, 0xFE70..0xFEFF),
            listOf("NotoNaskhArabic-Regular.ttf", "NotoSansArabic-Regular.ttf", "NotoNaskhArabicUI-Regular.ttf")
        ),
        Candidate(listOf(0x0900..0x097F), listOf("NotoSansDevanagari-Regular.ttf", "NotoSansDevanagariUI-Regular.ttf")),
        // Latin letters outside WinAnsi (for example macrons) and general symbols.
        Candidate(listOf(0x0100..0x024F, 0x1E00..0x1EFF, 0x2000..0x206F), listOf("NotoSans-Regular.ttf", "Roboto-Regular.ttf"))
    )

    private fun canEncode(font: PDFont, ch: String): Boolean = try {
        font.encode(ch); true
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun fontFor(cp: Int): PDFont? {
        val cand = candidates.firstOrNull { c -> c.ranges.any { cp in it } } ?: return null
        for (name in cand.fileNames) {
            val font = loaded.getOrPut(name) { load(name) }
            if (font != null) return font
        }
        return null
    }

    private fun load(name: String): PDFont? {
        for (dir in fontDirs) {
            val f = File(dir, name)
            if (!f.isFile) continue
            try {
                return PDType0Font.load(document, f)
            } catch (e: Exception) {
                // unreadable or unsupported font file: try the next candidate
            }
        }
        return null
    }

    /** Splits [text] into runs, each in a font that can encode it. Characters nobody can encode are counted and omitted. */
    fun runsFor(text: String): List<Run> {
        val runs = ArrayList<Run>()
        var curFont: PDFont? = null
        val cur = StringBuilder()
        fun flush() {
            val f = curFont
            if (f != null && cur.isNotEmpty()) runs.add(Run(f, cur.toString()))
            cur.setLength(0); curFont = null
        }
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val step = Character.charCount(cp)
            var s = String(Character.toChars(cp))
            if (s == "\n" || s == "\r" || s == "\t") s = " "
            var font: PDFont? = when {
                canEncode(base, s) -> base
                else -> fontFor(cp)?.takeIf { canEncode(it, s) }
            }
            if (font == null) {
                charactersDropped++
            } else {
                if (font !== curFont) { flush(); curFont = font }
                cur.append(s)
            }
            i += step
        }
        flush()
        // A line that is only whitespace has nothing to search for.
        return if (runs.all { it.text.isBlank() }) emptyList() else runs
    }
}
