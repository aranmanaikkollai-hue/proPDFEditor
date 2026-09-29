package com.propdfeditor.core.pdf.poc

import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdf.core.domain.model.FormFieldType
import com.propdf.core.domain.model.PdfFormField as DomainFormField
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceEntry
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * PHASE 4A / POC C - AcroForm handling with PDFBox Android 2.0.27.0.
 * ISOLATED: does not touch PdfFormEngine / PdfFormRepositoryImpl / PdfFormViewModel. iText stays.
 * The production Flow fix (.first() instead of endless .collect{}) is untouched and irrelevant here:
 * these tests only exercise the PDF library, never Room.
 *
 * FIXTURE: built entirely with PDFBox (no extra dependency). Every button-type widget gets a real
 * /AP /N dictionary with an on-state and an "Off" state, because PDCheckBox/PDRadioButton derive their
 * on-values from the appearance streams. Real-world forms that use /Opt export values (index-named
 * appearance states) are a DIFFERENT case that this fixture does NOT cover - see report gaps.
 */
@RunWith(AndroidJUnit4::class)
class PdfBoxFormsPocTest {

    companion object {
        private const val STATIC_TEXT = "STATIC-CONTENT-MARKER"
        private const val BOOKMARK = "BOOKMARK-ONE"

        // Field geometry in PDF space (A4, origin bottom-left)
        private val TEXT_RECT = PDRectangle(50f, 700f, 200f, 24f)
        private val CHECK_RECT = PDRectangle(50f, 650f, 14f, 14f)
        private val RADIO_RECTS = listOf(
            PDRectangle(50f, 600f, 14f, 14f),
            PDRectangle(90f, 600f, 14f, 14f),
            PDRectangle(130f, 600f, 14f, 14f)
        )
        private val COMBO_RECT = PDRectangle(50f, 550f, 150f, 22f)
        private val LIST_RECT = PDRectangle(50f, 450f, 150f, 60f)
        private val RADIO_OPTIONS = listOf("A", "B", "C")
        private val COMBO_OPTIONS = listOf("Red", "Green", "Blue")
        private val LIST_OPTIONS = listOf("One", "Two", "Three")

        private val EXPECTED_NAMES = setOf("name", "agree", "choice", "colour", "listing")

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            PocSupport.initPdfBox()
        }
    }

    private val temps = mutableListOf<File>()
    private fun newTemp(tag: String): File = PocSupport.tempPdf(tag).also { temps.add(it) }

    @After
    fun cleanup() {
        temps.forEach { it.delete() }
        temps.clear()
    }

    // ---- fixture ---------------------------------------------------------------------------------

    private fun stateStream(doc: PDDocument, on: Boolean): PDAppearanceStream {
        val stream = PDAppearanceStream(doc)
        stream.setBBox(PDRectangle(14f, 14f))
        stream.setResources(PDResources())
        val ops = if (on) {
            "q 0 G 0.75 w 0.5 0.5 13 13 re S 1.5 w 3 3 m 11 11 l S 3 11 m 11 3 l S Q"
        } else {
            "q 0 G 0.75 w 0.5 0.5 13 13 re S Q"
        }
        stream.cosObject.createOutputStream().use { it.write(ops.toByteArray(Charsets.US_ASCII)) }
        return stream
    }

    private fun stateAppearance(doc: PDDocument, onName: String): PDAppearanceDictionary {
        val n = COSDictionary()
        n.setItem(COSName.getPDFName(onName), stateStream(doc, true))
        n.setItem(COSName.getPDFName("Off"), stateStream(doc, false))
        val ap = PDAppearanceDictionary()
        ap.setNormalAppearance(PDAppearanceEntry(n))
        return ap
    }

    private fun placeWidget(widget: PDAnnotationWidget, page: PDPage, rect: PDRectangle) {
        widget.setRectangle(rect)
        widget.setPage(page)
        widget.setPrinted(true)
        page.annotations.add(widget)
    }

    private fun buildFixture(file: File) {
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)

            val acro = PDAcroForm(doc)
            doc.documentCatalog.setAcroForm(acro)
            val resources = PDResources()
            resources.put(COSName.getPDFName("Helv"), PDType1Font.HELVETICA)
            acro.setDefaultResources(resources)
            acro.setDefaultAppearance("/Helv 0 Tf 0 g")

            // Non-form content that must survive: text, image, bookmark.
            val bmp = PocSupport.testBitmap(120, 60, Color.rgb(255, 220, 220))
            try {
                val image = LosslessFactory.createFromImage(doc, bmp)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 14f)
                    cs.newLineAtOffset(50f, 800f)
                    cs.showText(STATIC_TEXT)
                    cs.endText()
                    cs.drawImage(image, 350f, 740f, 120f, 60f)
                }
            } finally {
                bmp.recycle()
            }
            val outline = PDDocumentOutline()
            doc.documentCatalog.setDocumentOutline(outline)
            val item = PDOutlineItem()
            item.setTitle(BOOKMARK)
            item.setDestination(page)
            outline.addLast(item)
            outline.openNode()

            // text field
            val tf = PDTextField(acro)
            tf.setPartialName("name")
            tf.setDefaultAppearance("/Helv 12 Tf 0 g")
            acro.fields.add(tf)
            placeWidget(tf.widgets[0], page, TEXT_RECT)

            // checkbox
            val cb = PDCheckBox(acro)
            cb.setPartialName("agree")
            acro.fields.add(cb)
            val cbWidget = cb.widgets[0]
            cbWidget.setAppearance(stateAppearance(doc, "Yes"))
            cbWidget.setAppearanceState("Off")
            placeWidget(cbWidget, page, CHECK_RECT)

            // radio group (state names == option values; NO /Opt export values, see class doc)
            val rb = PDRadioButton(acro)
            rb.setPartialName("choice")
            val radioWidgets = ArrayList<PDAnnotationWidget>()
            for (i in RADIO_OPTIONS.indices) {
                val w = PDAnnotationWidget()
                w.setAppearance(stateAppearance(doc, RADIO_OPTIONS[i]))
                w.setAppearanceState("Off")
                placeWidget(w, page, RADIO_RECTS[i])
                radioWidgets.add(w)
            }
            rb.setWidgets(radioWidgets)
            acro.fields.add(rb)

            // combo box
            val combo = PDComboBox(acro)
            combo.setPartialName("colour")
            combo.setDefaultAppearance("/Helv 10 Tf 0 g")
            combo.setOptions(COMBO_OPTIONS)
            acro.fields.add(combo)
            placeWidget(combo.widgets[0], page, COMBO_RECT)

            // list box
            val list = PDListBox(acro)
            list.setPartialName("listing")
            list.setDefaultAppearance("/Helv 10 Tf 0 g")
            list.setOptions(LIST_OPTIONS)
            acro.fields.add(list)
            placeWidget(list.widgets[0], page, LIST_RECT)

            doc.save(file)
        }
    }

    private fun newFixture(tag: String): File = newTemp(tag).also { buildFixture(it) }

    /** Loads [src], applies [edit], saves to a new temp file and returns it. */
    private fun fillAndSave(src: File, tag: String, edit: (PDDocument, PDAcroForm) -> Unit): File {
        val dst = newTemp(tag)
        PDDocument.load(src).use { doc ->
            val acro = doc.documentCatalog.acroForm
            assertNotNull("fixture lost its AcroForm", acro)
            edit(doc, acro!!)
            doc.save(dst)
        }
        return dst
    }

    private fun mapType(f: PDField): FormFieldType = when (f) {
        is PDTextField -> FormFieldType.TEXTBOX
        is PDCheckBox -> FormFieldType.CHECKBOX
        is PDRadioButton -> FormFieldType.RADIO_BUTTON
        is PDComboBox -> FormFieldType.DROPDOWN
        is PDListBox -> FormFieldType.LIST_BOX
        else -> FormFieldType.UNKNOWN
    }

    // C1 ---------------------------------------------------------------------------------------
    @Test
    fun c1_loadExistingForm_enumeratesFieldsAndTypes() {
        val file = newFixture("c1")
        PDDocument.load(file).use { doc ->
            val acro = doc.documentCatalog.acroForm
            assertNotNull(acro)
            val found = HashMap<String, FormFieldType>()
            for (f in acro!!.fieldTree) found[f.fullyQualifiedName] = mapType(f)
            assertEquals(EXPECTED_NAMES, found.keys)
            assertEquals(FormFieldType.TEXTBOX, found["name"])
            assertEquals(FormFieldType.CHECKBOX, found["agree"])
            assertEquals(FormFieldType.RADIO_BUTTON, found["choice"])
            assertEquals(FormFieldType.DROPDOWN, found["colour"])
            assertEquals(FormFieldType.LIST_BOX, found["listing"])
        }
    }

    // C2 ---------------------------------------------------------------------------------------
    @Test
    fun c2_textField_valueSurvivesSaveAndReopen() {
        val out = fillAndSave(newFixture("c2src"), "c2") { _, acro ->
            (acro.getField("name") as PDTextField).setValue("Test User")
        }
        PDDocument.load(out).use { doc ->
            val f = doc.documentCatalog.acroForm!!.getField("name") as PDTextField
            assertEquals("Test User", f.getValue())
        }
    }

    // C3 ---------------------------------------------------------------------------------------
    @Test
    fun c3_checkbox_checkedThenUnchecked() {
        val checked = fillAndSave(newFixture("c3src"), "c3on") { _, acro ->
            (acro.getField("agree") as PDCheckBox).check()
        }
        PDDocument.load(checked).use { doc ->
            assertTrue((doc.documentCatalog.acroForm!!.getField("agree") as PDCheckBox).isChecked)
        }
        val unchecked = fillAndSave(checked, "c3off") { _, acro ->
            (acro.getField("agree") as PDCheckBox).unCheck()
        }
        PDDocument.load(unchecked).use { doc ->
            assertFalse((doc.documentCatalog.acroForm!!.getField("agree") as PDCheckBox).isChecked)
        }
    }

    // C4 ---------------------------------------------------------------------------------------
    @Test
    fun c4_radio_selectedOptionPreserved_othersOff() {
        val out = fillAndSave(newFixture("c4src"), "c4") { _, acro ->
            (acro.getField("choice") as PDRadioButton).setValue("B")
        }
        PDDocument.load(out).use { doc ->
            val rb = doc.documentCatalog.acroForm!!.getField("choice") as PDRadioButton
            assertEquals("B", rb.getValue())
            val states = rb.widgets.map { it.appearanceState }
            assertEquals("widget states: $states", listOf("Off", "B", "Off"), states)
        }
    }

    // C5 ---------------------------------------------------------------------------------------
    @Test
    fun c5_comboBox_selectedValuePreserved() {
        val out = fillAndSave(newFixture("c5src"), "c5") { _, acro ->
            (acro.getField("colour") as PDComboBox).setValue("Green")
        }
        PDDocument.load(out).use { doc ->
            val combo = doc.documentCatalog.acroForm!!.getField("colour") as PDComboBox
            assertEquals(listOf("Green"), combo.getValue())
            assertEquals(COMBO_OPTIONS, combo.options)
        }
    }

    // C6 ---------------------------------------------------------------------------------------
    @Test
    fun c6_listBox_optionsEnumeratedAndSelectionPreserved() {
        val out = fillAndSave(newFixture("c6src"), "c6") { _, acro ->
            val list = acro.getField("listing") as PDListBox
            assertEquals(LIST_OPTIONS, list.options)
            list.setValue("Two")
        }
        PDDocument.load(out).use { doc ->
            val list = doc.documentCatalog.acroForm!!.getField("listing") as PDListBox
            assertEquals(listOf("Two"), list.getValue())
        }
    }

    // C7 ---------------------------------------------------------------------------------------
    /**
     * PDFBox field -> (name, type, value) -> the app's existing DomainFormField, one entry per widget
     * (same granularity as PdfFormEngine.extractFields). Documented mismatches are asserted, not hidden.
     */
    @Test
    fun c7_valueExtraction_mapsIntoExistingDomainModel() {
        val filled = fillAndSave(newFixture("c7src"), "c7") { _, acro ->
            (acro.getField("name") as PDTextField).setValue("Test User")
            (acro.getField("agree") as PDCheckBox).check()
            (acro.getField("choice") as PDRadioButton).setValue("C")
            (acro.getField("colour") as PDComboBox).setValue("Blue")
            (acro.getField("listing") as PDListBox).setValue("One")
        }
        val mapped = ArrayList<DomainFormField>()
        PDDocument.load(filled).use { doc ->
            val acro = doc.documentCatalog.acroForm!!
            for (f in acro.fieldTree) {
                val type = mapType(f)
                // PDChoice.getValueAsString() is array-formatted in the 2.0.x source; use getValue().
                val value: String? = if (f is PDChoice) f.getValue().firstOrNull() else f.valueAsString
                for (w in f.widgets) {
                    val r = w.rectangle
                    mapped.add(
                        DomainFormField(
                            documentUri = filled.toURI().toString(),
                            fieldName = f.fullyQualifiedName,
                            fieldType = type,
                            pageIndex = doc.pages.indexOf(w.page),
                            rect = RectF(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY),
                            value = value,
                            defaultValue = f.cosObject.getString(COSName.DV),
                            options = (f as? PDChoice)?.options ?: emptyList(),
                            isRequired = f.isRequired,
                            isReadOnly = f.isReadOnly,
                            groupName = if (type == FormFieldType.RADIO_BUTTON) f.fullyQualifiedName else null
                        )
                    )
                }
            }
        }
        assertEquals("one entry per widget (1 + 1 + 3 + 1 + 1)", 7, mapped.size)
        assertEquals(EXPECTED_NAMES, mapped.map { it.fieldName }.toSet())
        assertEquals("Test User", mapped.first { it.fieldName == "name" }.value)
        assertEquals("Yes", mapped.first { it.fieldName == "agree" }.value)
        assertTrue(mapped.filter { it.fieldName == "choice" }.all { it.value == "C" && it.pageIndex == 0 })
        assertEquals("Blue", mapped.first { it.fieldName == "colour" }.value)
        assertEquals(COMBO_OPTIONS, mapped.first { it.fieldName == "colour" }.options)
        assertEquals("One", mapped.first { it.fieldName == "listing" }.value)
    }

    // C8 ---------------------------------------------------------------------------------------
    /** Values are not enough: the saved PDF must SHOW them. Compares 72dpi renders before/after. */
    @Test
    fun c8_appearance_valuesAreVisibleInRenderedPage() {
        val fixture = newFixture("c8src")
        val filled = fillAndSave(fixture, "c8") { _, acro ->
            (acro.getField("name") as PDTextField).setValue("Test User")
            (acro.getField("agree") as PDCheckBox).check()
            (acro.getField("choice") as PDRadioButton).setValue("B")
            (acro.getField("colour") as PDComboBox).setValue("Green")
            (acro.getField("listing") as PDListBox).setValue("Two")
        }
        PDDocument.load(fixture).use { beforeDoc ->
            PDDocument.load(filled).use { afterDoc ->
                val before = PocSupport.render(beforeDoc, 0)
                val after = PocSupport.render(afterDoc, 0)
                try {
                    fun ink(bmp: android.graphics.Bitmap, r: PDRectangle) =
                        PocSupport.inkInPdfRect(bmp, r.lowerLeftX, r.lowerLeftY, r.width, r.height)

                    val textBefore = ink(before, TEXT_RECT)
                    val textAfter = ink(after, TEXT_RECT)
                    assertTrue("text field not rendered ($textBefore -> $textAfter)", textAfter > textBefore + 30)

                    val cbBefore = ink(before, CHECK_RECT)
                    val cbAfter = ink(after, CHECK_RECT)
                    assertTrue("checkbox on-state not rendered ($cbBefore -> $cbAfter)", cbAfter > cbBefore + 5)

                    // radio: chosen widget (B) gains ink, the other two are unchanged
                    assertTrue("radio B not rendered", ink(after, RADIO_RECTS[1]) > ink(before, RADIO_RECTS[1]) + 5)
                    assertEquals("radio A changed", ink(before, RADIO_RECTS[0]), ink(after, RADIO_RECTS[0]))
                    assertEquals("radio C changed", ink(before, RADIO_RECTS[2]), ink(after, RADIO_RECTS[2]))

                    assertTrue("combo not rendered", ink(after, COMBO_RECT) > ink(before, COMBO_RECT) + 10)
                    assertTrue("list not rendered", ink(after, LIST_RECT) > ink(before, LIST_RECT) + 10)
                } finally {
                    before.recycle(); after.recycle()
                }
            }
        }
    }

    // C9 ---------------------------------------------------------------------------------------
    @Test
    fun c9_flatten_valuesBecomeStaticContent_fieldsDisappear() {
        val fixture = newFixture("c9src")
        val flattened = fillAndSave(fixture, "c9") { _, acro ->
            (acro.getField("name") as PDTextField).setValue("Test User")
            (acro.getField("agree") as PDCheckBox).check()
            (acro.getField("choice") as PDRadioButton).setValue("B")
            (acro.getField("colour") as PDComboBox).setValue("Green")
            (acro.getField("listing") as PDListBox).setValue("Two")
            acro.flatten()
        }
        PDDocument.load(fixture).use { beforeDoc ->
            PDDocument.load(flattened).use { doc ->
                val acro = doc.documentCatalog.acroForm
                assertTrue("form still interactive after flatten", acro == null || !acro.fieldTree.iterator().hasNext())
                val widgets = doc.getPage(0).annotations.filterIsInstance<PDAnnotationWidget>()
                assertTrue("widgets remain after flatten: ${widgets.size}", widgets.isEmpty())

                val before = PocSupport.render(beforeDoc, 0)
                val after = PocSupport.render(doc, 0)
                try {
                    val textBefore = PocSupport.inkInPdfRect(
                        before, TEXT_RECT.lowerLeftX, TEXT_RECT.lowerLeftY, TEXT_RECT.width, TEXT_RECT.height
                    )
                    val textAfter = PocSupport.inkInPdfRect(
                        after, TEXT_RECT.lowerLeftX, TEXT_RECT.lowerLeftY, TEXT_RECT.width, TEXT_RECT.height
                    )
                    assertTrue("flattened text not visible ($textBefore -> $textAfter)", textAfter > textBefore + 30)
                } finally {
                    before.recycle(); after.recycle()
                }
                // Once flattened the value is ordinary page content and is extractable.
                assertTrue(PocSupport.pageText(doc, 1).contains("Test User"))
            }
        }
    }

    // C10 --------------------------------------------------------------------------------------
    @Test
    fun c10_contentPreservation_afterFill() {
        val filled = fillAndSave(newFixture("c10src"), "c10") { _, acro ->
            (acro.getField("name") as PDTextField).setValue("Test User")
            (acro.getField("agree") as PDCheckBox).check()
        }
        PDDocument.load(filled).use { doc ->
            assertEquals("page count", 1, doc.numberOfPages)
            assertTrue("static text lost", PocSupport.pageText(doc, 1).contains(STATIC_TEXT))
            assertEquals("image lost", 1, PocSupport.imageCount(doc.getPage(0)))
            val outline = doc.documentCatalog.documentOutline
            assertNotNull("bookmarks lost", outline)
            assertEquals(BOOKMARK, outline!!.firstChild.title)
            val acro = doc.documentCatalog.acroForm
            assertNotNull(acro)
            assertEquals(EXPECTED_NAMES, acro!!.fieldTree.map { it.fullyQualifiedName }.toSet())
            assertNull("unexpected extra field values", (acro.getField("colour") as PDComboBox).getValue().firstOrNull())
        }
    }
}
