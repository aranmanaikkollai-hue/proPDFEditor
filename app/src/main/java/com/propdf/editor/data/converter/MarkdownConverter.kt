package com.propdf.editor.data.converter

import android.content.Context
import android.net.Uri
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.draw.SolidLine
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.LineSeparator
import com.itextpdf.layout.element.Paragraph as PdfParagraph
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import com.propdf.editor.domain.model.ConversionResult
import com.propdf.editor.utils.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.Extension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

@Singleton
class MarkdownConverter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val FONT_SIZE_NORMAL = 11f
        private const val FONT_SIZE_H1 = 24f
        private const val FONT_SIZE_H2 = 20f
        private const val FONT_SIZE_H3 = 16f
        private const val FONT_SIZE_CODE = 9f
        private const val MARGIN = 36f
    }
    
    private val extensions: List<Extension> = listOf(TablesExtension.create())
    
    suspend fun toPdf(
        context: Context,
        sourceUri: Uri,
        outputDir: File,
        fileName: String,
        onProgress: suspend (Int) -> Unit
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            val markdownContent = readMarkdownContent(context, sourceUri)
                ?: return@withContext ConversionResult(false, null, fileName, "Cannot read Markdown")
            
            onProgress(20)
            
            if (!coroutineContext.isActive) {
                return@withContext ConversionResult(false, null, fileName, "Cancelled")
            }
            
            // Parse markdown to AST
            val parser = Parser.builder()
                .extensions(extensions)
                .build()
            
            val document = parser.parse(markdownContent)
            
            onProgress(40)
            
            // Convert to PDF
            val outputFile = File(outputDir, "$fileName.pdf")
            val writer = PdfWriter(outputFile.absolutePath)
            val pdfDoc = PdfDocument(writer)
            val pdfDocument = Document(pdfDoc, PageSize.A4)
            
            pdfDocument.setMargins(MARGIN, MARGIN, MARGIN, MARGIN)
            
            val font = PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA)
            val fontBold = PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.HELVETICA_BOLD)
            val fontMono = PdfFontFactory.createFont(com.itextpdf.io.font.constants.StandardFonts.COURIER)
            
            var nodeCount = 0
            var processedCount = 0
            
            // Count nodes for progress
            nodeCount = countNodes(document)
            
            // Render nodes
            val progressScope = CoroutineScope(coroutineContext)
            document.accept(object : AbstractVisitor() {
                override fun visit(heading: Heading) {
                    processedCount++
                    val fontSize = when (heading.level) {
                        1 -> FONT_SIZE_H1
                        2 -> FONT_SIZE_H2
                        else -> FONT_SIZE_H3
                    }
                    
                    val text = extractText(heading)
                    val paragraph = PdfParagraph(text)
                        .setFont(fontBold)
                        .setFontSize(fontSize)
                        .setMarginTop(if (heading.level == 1) 0f else 12f)
                        .setMarginBottom(8f)
                    
                    pdfDocument.add(paragraph)
                    progressScope.launch { updateProgress(processedCount, nodeCount, onProgress) }
                }
                
                override fun visit(paragraph: Paragraph) {
                    processedCount++
                    // Skip if parent is list item (handled separately)
                    if (paragraph.parent is ListItem) {
                        super.visit(paragraph)
                        return
                    }
                    
                    val text = renderInlineNodes(paragraph, font, fontBold, fontMono)
                    val pdfParagraph = PdfParagraph()
                        .setFont(font)
                        .setFontSize(FONT_SIZE_NORMAL)
                        .setMarginBottom(8f)
                    
                    // Add all children as text
                    var currentText = com.itextpdf.layout.element.Text("")
                    paragraph.accept(object : AbstractVisitor() {
                        override fun visit(text: Text) {
                            val t = com.itextpdf.layout.element.Text(text.literal)
                                .setFont(font)
                                .setFontSize(FONT_SIZE_NORMAL)
                            pdfParagraph.add(t)
                        }
                        
                        override fun visit(emphasis: Emphasis) {
                            val t = com.itextpdf.layout.element.Text(extractText(emphasis))
                                .setFont(font)
                                .setFontSize(FONT_SIZE_NORMAL)
                                .setItalic()
                            pdfParagraph.add(t)
                        }
                        
                        override fun visit(strongEmphasis: StrongEmphasis) {
                            val t = com.itextpdf.layout.element.Text(extractText(strongEmphasis))
                                .setFont(fontBold)
                                .setFontSize(FONT_SIZE_NORMAL)
                            pdfParagraph.add(t)
                        }
                        
                        override fun visit(code: Code) {
                            val t = com.itextpdf.layout.element.Text(code.literal)
                                .setFont(fontMono)
                                .setFontSize(FONT_SIZE_CODE)
                                .setBackgroundColor(com.itextpdf.kernel.colors.ColorConstants.LIGHT_GRAY)
                            pdfParagraph.add(t)
                        }
                        
                        override fun visit(link: Link) {
                            val t = com.itextpdf.layout.element.Text(link.title ?: link.destination)
                                .setFont(font)
                                .setFontSize(FONT_SIZE_NORMAL)
                                .setFontColor(com.itextpdf.kernel.colors.ColorConstants.BLUE)
                                .setUnderline()
                            pdfParagraph.add(t)
                        }
                    })
                    
                    if (pdfParagraph.children.isEmpty()) {
                        val t = com.itextpdf.layout.element.Text(extractText(paragraph))
                            .setFont(font)
                            .setFontSize(FONT_SIZE_NORMAL)
                        pdfParagraph.add(t)
                    }
                    
                    pdfDocument.add(pdfParagraph)
                    progressScope.launch { updateProgress(processedCount, nodeCount, onProgress) }
                }
                
                override fun visit(bulletList: BulletList) {
                    processedCount++
                    bulletList.accept(object : AbstractVisitor() {
                        override fun visit(listItem: ListItem) {
                            val text = "• ${extractText(listItem).trim()}"
                            val paragraph = PdfParagraph(text)
                                .setFont(font)
                                .setFontSize(FONT_SIZE_NORMAL)
                                .setMarginLeft(20f)
                                .setMarginBottom(4f)
                            pdfDocument.add(paragraph)
                        }
                    })
                    progressScope.launch { updateProgress(processedCount, nodeCount, onProgress) }
                }
                
                override fun visit(orderedList: OrderedList) {
                    processedCount++
                    var index = 1
                    orderedList.accept(object : AbstractVisitor() {
                        override fun visit(listItem: ListItem) {
                            val text = "$index. ${extractText(listItem).trim()}"
                            val paragraph = PdfParagraph(text)
                                .setFont(font)
                                .setFontSize(FONT_SIZE_NORMAL)
                                .setMarginLeft(20f)
                                .setMarginBottom(4f)
                            pdfDocument.add(paragraph)
                            index++
                        }
                    })
                    progressScope.launch { updateProgress(processedCount, nodeCount, onProgress) }
                }
                
                override fun visit(codeBlock: CodeBlock) {
                    processedCount++
                    val text = codeBlock.literal
                    val paragraph = PdfParagraph(text)
                        .setFont(fontMono)
                        .setFontSize(FONT_SIZE_CODE)
                        .setBackgroundColor(com.itextpdf.kernel.colors.ColorConstants.LIGHT_GRAY)
                        .setPadding(8f)
                        .setMarginBottom(8f)
                    pdfDocument.add(paragraph)
                    progressScope.launch { updateProgress(processedCount, nodeCount, onProgress) }
                }
                
                override fun visit(blockQuote: BlockQuote) {
                    processedCount++
                    val text = extractText(blockQuote)
                    val paragraph = PdfParagraph(text)
                        .setFont(font)
                        .setFontSize(FONT_SIZE_NORMAL)
                        .setFontColor(com.itextpdf.kernel.colors.ColorConstants.DARK_GRAY)
                        .setMarginLeft(20f)
                        .setBorderLeft(
                            com.itextpdf.layout.borders.SolidBorder(
                                com.itextpdf.kernel.colors.ColorConstants.GRAY,
                                3f
                            )
                        )
                        .setPaddingLeft(8f)
                        .setMarginBottom(8f)
                    pdfDocument.add(paragraph)
                    progressScope.launch { updateProgress(processedCount, nodeCount, onProgress) }
                }
                
                override fun visit(thematicBreak: ThematicBreak) {
                    processedCount++
                    val lineDrawer = SolidLine(1f).apply {
                        color = com.itextpdf.kernel.colors.ColorConstants.GRAY
                    }
                    val line = LineSeparator(lineDrawer)
                    pdfDocument.add(line)
                    progressScope.launch { updateProgress(processedCount, nodeCount, onProgress) }
                }
            })
            
            onProgress(90)
            
            pdfDocument.close()
            pdfDoc.close()
            
            onProgress(100)
            
            val outputUri = FileUtils.getUriForFile(context, outputFile)
            ConversionResult(
                true,
                outputUri,
                fileName,
                "Converted Markdown to PDF",
                totalBytes = outputFile.length()
            )
            
        } catch (e: Exception) {
            ConversionResult(false, null, fileName, e.message ?: "Markdown to PDF failed")
        }
    }
    
    private suspend fun updateProgress(current: Int, total: Int, onProgress: suspend (Int) -> Unit) {
        if (total > 0) {
            onProgress(40 + ((current * 50) / total))
        }
    }
    
    private fun readMarkdownContent(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun countNodes(node: Node): Int {
        var count = 1
        var child = node.firstChild
        while (child != null) {
            count += countNodes(child)
            child = child.next
        }
        return count
    }
    
    private fun extractText(node: Node): String {
        val builder = StringBuilder()
        node.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                builder.append(text.literal)
            }
        })
        return builder.toString()
    }
    
    private fun renderInlineNodes(
        paragraph: Paragraph,
        font: com.itextpdf.kernel.font.PdfFont,
        fontBold: com.itextpdf.kernel.font.PdfFont,
        fontMono: com.itextpdf.kernel.font.PdfFont
    ): PdfParagraph {
        val pdfParagraph = PdfParagraph()
            .setFont(font)
            .setFontSize(FONT_SIZE_NORMAL)
        
        paragraph.accept(object : AbstractVisitor() {
            override fun visit(text: Text) {
                pdfParagraph.add(com.itextpdf.layout.element.Text(text.literal)
                    .setFont(font)
                    .setFontSize(FONT_SIZE_NORMAL))
            }
            
            override fun visit(emphasis: Emphasis) {
                pdfParagraph.add(com.itextpdf.layout.element.Text(extractText(emphasis))
                    .setFont(font)
                    .setFontSize(FONT_SIZE_NORMAL)
                    .setItalic())
            }
            
            override fun visit(strongEmphasis: StrongEmphasis) {
                pdfParagraph.add(com.itextpdf.layout.element.Text(extractText(strongEmphasis))
                    .setFont(fontBold)
                    .setFontSize(FONT_SIZE_NORMAL))
            }
            
            override fun visit(code: Code) {
                pdfParagraph.add(com.itextpdf.layout.element.Text(code.literal)
                    .setFont(fontMono)
                    .setFontSize(FONT_SIZE_CODE)
                    .setBackgroundColor(com.itextpdf.kernel.colors.ColorConstants.LIGHT_GRAY))
            }
        })
        
        return pdfParagraph
    }
}
