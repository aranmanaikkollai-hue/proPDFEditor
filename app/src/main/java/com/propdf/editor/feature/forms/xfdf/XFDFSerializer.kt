package com.propdf.editor.feature.forms.xfdf

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * XFDF (XML Forms Data Format) serializer/deserializer.
 * Compatible with Adobe Acrobat XFDF specification.
 */
class XFDFSerializer {

    fun serialize(values: Map<String, String>): String {
        val doc = createDocument()
        val root = doc.createElement("xfdf")
        root.setAttribute("xmlns", "http://ns.adobe.com/xfdf/")
        root.setAttribute("xml:space", "preserve")
        doc.appendChild(root)

        val fields = doc.createElement("fields")
        root.appendChild(fields)

        values.forEach { (name, value) ->
            val field = doc.createElement("field")
            field.setAttribute("name", name)

            val valueElement = doc.createElement("value")
            valueElement.textContent = value
            field.appendChild(valueElement)

            fields.appendChild(field)
        }

        return transformToString(doc)
    }

    fun deserialize(xfdfData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xfdfData))

        var currentFieldName: String? = null
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "field" -> currentFieldName = parser.getAttributeValue(null, "name")
                        "value" -> {
                            currentFieldName?.let { name ->
                                result[name] = parser.nextText()
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "field") {
                        currentFieldName = null
                    }
                }
            }
            eventType = parser.next()
        }

        return result
    }

    fun createEmptyXFDF(): String {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
               "<xfdf xmlns=\"http://ns.adobe.com/xfdf/\" xml:space=\"preserve\">\n" +
               "    <fields/>\n" +
               "</xfdf>"
    }

    private fun createDocument(): Document {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
    }

    private fun transformToString(doc: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }
}
