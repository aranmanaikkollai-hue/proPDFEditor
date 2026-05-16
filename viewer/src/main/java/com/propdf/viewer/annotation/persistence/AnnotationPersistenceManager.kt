package com.propdf.viewer.annotation.persistence

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.propdf.viewer.annotation.model.Annotation
import com.propdf.viewer.annotation.model.AnnotationState
import com.propdf.viewer.annotation.model.AnnotationType
import com.propdf.viewer.annotation.model.ImageStampAnnotation
import com.propdf.viewer.annotation.model.InkAnnotation
import com.propdf.viewer.annotation.model.ShapeAnnotation
import com.propdf.viewer.annotation.model.SignatureAnnotation
import com.propdf.viewer.annotation.model.Stroke
import com.propdf.viewer.annotation.model.StickyNoteAnnotation
import com.propdf.viewer.annotation.model.TextCommentAnnotation
import com.propdf.viewer.annotation.model.TextMarkupAnnotation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter

class AnnotationPersistenceManager(private val context: Context) {

    private val annotationsDir by lazy {
        File(context.filesDir, "annotations").apply { mkdirs() }
    }

    fun getAnnotationFile(pdfUri: String): File {
        val hash = pdfUri.hashCode().toString()
        return File(annotationsDir, "${hash}.json")
    }

    suspend fun saveAnnotations(pdfUri: String, annotations: List<Annotation>) = withContext(Dispatchers.IO) {
        try {
            val file = getAnnotationFile(pdfUri)
            val jsonArray = JSONArray()
            annotations.forEach { annotation ->
                jsonArray.put(serializeAnnotation(annotation))
            }
            FileWriter(file).use { writer ->
                writer.write(jsonArray.toString(2))
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun loadAnnotations(pdfUri: String): List<Annotation> = withContext(Dispatchers.IO) {
        try {
            val file = getAnnotationFile(pdfUri)
            if (!file.exists()) return@withContext emptyList()

            val jsonStr = file.readText()
            val jsonArray = JSONArray(jsonStr)
            val annotations = mutableListOf<Annotation>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                deserializeAnnotation(obj)?.let { ann -> annotations.add(ann) }
            }
            annotations
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    suspend fun deleteAnnotations(pdfUri: String) = withContext(Dispatchers.IO) {
        getAnnotationFile(pdfUri).delete()
    }

    private fun serializeAnnotation(annotation: Annotation): JSONObject {
        val obj = JSONObject()
        obj.put("id", annotation.id)
        obj.put("pageIndex", annotation.pageIndex)
        obj.put("type", annotation.type.name)
        obj.put("color", annotation.color)
        obj.put("alpha", annotation.alpha.toDouble())
        obj.put("strokeWidth", annotation.strokeWidth.toDouble())
        obj.put("state", annotation.state.name)
        obj.put("createdAt", annotation.createdAt)
        obj.put("zIndex", annotation.zIndex)

        when (annotation) {
            is TextMarkupAnnotation -> {
                obj.put("bounds", serializeRect(annotation.bounds))
                obj.put("quads", serializeRects(annotation.quads))
            }
            is InkAnnotation -> {
                obj.put("strokes", serializeStrokes(annotation.strokes))
            }
            is TextCommentAnnotation -> {
                obj.put("anchorX", annotation.anchorX.toDouble())
                obj.put("anchorY", annotation.anchorY.toDouble())
                obj.put("text", annotation.text)
                obj.put("author", annotation.author)
                obj.put("icon", annotation.icon)
            }
            is StickyNoteAnnotation -> {
                obj.put("x", annotation.x.toDouble())
                obj.put("y", annotation.y.toDouble())
                obj.put("width", annotation.width.toDouble())
                obj.put("height", annotation.height.toDouble())
                obj.put("text", annotation.text)
                obj.put("author", annotation.author)
            }
            is ShapeAnnotation -> {
                obj.put("bounds", serializeRect(annotation.bounds))
                annotation.fillColor?.let { fc -> obj.put("fillColor", fc) }
                obj.put("fillAlpha", annotation.fillAlpha.toDouble())
                obj.put("startArrow", annotation.startArrow)
                obj.put("endArrow", annotation.endArrow)
            }
            is SignatureAnnotation -> {
                obj.put("strokes", serializeStrokes(annotation.strokes))
                obj.put("bounds", serializeRect(annotation.bounds))
                obj.put("signerName", annotation.signerName)
                obj.put("timestamp", annotation.timestamp)
            }
            is ImageStampAnnotation -> {
                obj.put("x", annotation.x.toDouble())
                obj.put("y", annotation.y.toDouble())
                obj.put("width", annotation.width.toDouble())
                obj.put("height", annotation.height.toDouble())
                obj.put("imageUri", annotation.imageUri)
                obj.put("rotation", annotation.rotation.toDouble())
            }
        }
        return obj
    }

    private fun deserializeAnnotation(obj: JSONObject): Annotation? {
        return try {
            val id = obj.getString("id")
            val pageIndex = obj.getInt("pageIndex")
            val type = AnnotationType.valueOf(obj.getString("type"))
            val color = obj.getInt("color")
            val alpha = obj.getDouble("alpha").toFloat()
            val strokeWidth = obj.getDouble("strokeWidth").toFloat()
            val state = AnnotationState.valueOf(obj.getString("state"))
            val createdAt = obj.getLong("createdAt")
            val zIndex = obj.getInt("zIndex")

            when (type) {
                AnnotationType.HIGHLIGHT, AnnotationType.UNDERLINE, AnnotationType.STRIKEOUT -> {
                    TextMarkupAnnotation(
                        id = id,
                        pageIndex = pageIndex,
                        type = type,
                        color = color,
                        alpha = alpha,
                        strokeWidth = strokeWidth,
                        state = state,
                        createdAt = createdAt,
                        zIndex = zIndex,
                        bounds = deserializeRect(obj.getJSONObject("bounds")),
                        quads = deserializeRects(obj.optJSONArray("quads"))
                    )
                }
                AnnotationType.FREEHAND, AnnotationType.PENCIL -> {
                    InkAnnotation(
                        id = id,
                        pageIndex = pageIndex,
                        type = type,
                        color = color,
                        alpha = alpha,
                        strokeWidth = strokeWidth,
                        state = state,
                        createdAt = createdAt,
                        zIndex = zIndex,
                        strokes = deserializeStrokes(obj.getJSONArray("strokes"))
                    )
                }
                AnnotationType.TEXT_COMMENT -> {
                    TextCommentAnnotation(
                        id = id,
                        pageIndex = pageIndex,
                        color = color,
                        alpha = alpha,
                        strokeWidth = strokeWidth,
                        state = state,
                        createdAt = createdAt,
                        zIndex = zIndex,
                        anchorX = obj.getDouble("anchorX").toFloat(),
                        anchorY = obj.getDouble("anchorY").toFloat(),
                        text = obj.optString("text", ""),
                        author = obj.optString("author", ""),
                        icon = obj.optString("icon", "Note")
                    )
                }
                AnnotationType.STICKY_NOTE -> {
                    StickyNoteAnnotation(
                        id = id,
                        pageIndex = pageIndex,
                        color = color,
                        alpha = alpha,
                        strokeWidth = strokeWidth,
                        state = state,
                        createdAt = createdAt,
                        zIndex = zIndex,
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        width = obj.optDouble("width", 0.15).toFloat(),
                        height = obj.optDouble("height", 0.15).toFloat(),
                        text = obj.optString("text", ""),
                        author = obj.optString("author", "")
                    )
                }
                AnnotationType.ARROW, AnnotationType.RECTANGLE, AnnotationType.CIRCLE -> {
                    ShapeAnnotation(
                        id = id,
                        pageIndex = pageIndex,
                        type = type,
                        color = color,
                        alpha = alpha,
                        strokeWidth = strokeWidth,
                        state = state,
                        createdAt = createdAt,
                        zIndex = zIndex,
                        bounds = deserializeRect(obj.getJSONObject("bounds")),
                        fillColor = if (obj.has("fillColor")) obj.getInt("fillColor") else null,
                        fillAlpha = obj.optDouble("fillAlpha", 0.2).toFloat(),
                        startArrow = obj.optBoolean("startArrow", false),
                        endArrow = obj.optBoolean("endArrow", false)
                    )
                }
                AnnotationType.SIGNATURE -> {
                    SignatureAnnotation(
                        id = id,
                        pageIndex = pageIndex,
                        color = color,
                        alpha = alpha,
                        strokeWidth = strokeWidth,
                        state = state,
                        createdAt = createdAt,
                        zIndex = zIndex,
                        strokes = deserializeStrokes(obj.getJSONArray("strokes")),
                        bounds = deserializeRect(obj.getJSONObject("bounds")),
                        signerName = obj.optString("signerName", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                    )
                }
                AnnotationType.IMAGE_STAMP -> {
                    ImageStampAnnotation(
                        id = id,
                        pageIndex = pageIndex,
                        color = color,
                        alpha = alpha,
                        strokeWidth = strokeWidth,
                        state = state,
                        createdAt = createdAt,
                        zIndex = zIndex,
                        x = obj.getDouble("x").toFloat(),
                        y = obj.getDouble("y").toFloat(),
                        width = obj.optDouble("width", 0.2).toFloat(),
                        height = obj.optDouble("height", 0.2).toFloat(),
                        imageUri = obj.optString("imageUri", ""),
                        rotation = obj.optDouble("rotation", 0.0).toFloat()
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun serializeRect(rect: RectF): JSONObject {
        return JSONObject().apply {
            put("left", rect.left.toDouble())
            put("top", rect.top.toDouble())
            put("right", rect.right.toDouble())
            put("bottom", rect.bottom.toDouble())
        }
    }

    private fun deserializeRect(obj: JSONObject): RectF {
        return RectF(
            obj.getDouble("left").toFloat(),
            obj.getDouble("top").toFloat(),
            obj.getDouble("right").toFloat(),
            obj.getDouble("bottom").toFloat()
        )
    }

    private fun serializeRects(rects: List<RectF>): JSONArray {
        return JSONArray().apply { rects.forEach { rect -> put(serializeRect(rect)) } }
    }

    private fun deserializeRects(array: JSONArray?): List<RectF> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { index -> deserializeRect(array.getJSONObject(index)) }
    }

    private fun serializeStrokes(strokes: List<Stroke>): JSONArray {
        return JSONArray().apply {
            strokes.forEach { stroke ->
                val strokeObj = JSONObject()
                val points = JSONArray()
                stroke.points.forEach { pt ->
                    points.put(JSONObject().apply {
                        put("x", pt.x.toDouble())
                        put("y", pt.y.toDouble())
                    })
                }
                strokeObj.put("points", points)
                val pressures = JSONArray()
                stroke.pressures.forEach { p -> pressures.put(p.toDouble()) }
                strokeObj.put("pressures", pressures)
                strokeObj.put("timestamp", stroke.timestamp)
                put(strokeObj)
            }
        }
    }

    private fun deserializeStrokes(array: JSONArray): List<Stroke> {
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val pointsArray = obj.getJSONArray("points")
            val points = (0 until pointsArray.length()).map { j ->
                val p = pointsArray.getJSONObject(j)
                PointF(p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
            }
            val pressuresArray = obj.optJSONArray("pressures")
            val pressures = if (pressuresArray != null) {
                (0 until pressuresArray.length()).map { idx -> pressuresArray.getDouble(idx).toFloat() }
            } else {
                List(points.size) { 1.0f }
            }
            Stroke(points, pressures, obj.optLong("timestamp", System.currentTimeMillis()))
        }
    }
}
