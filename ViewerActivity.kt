package com.propdf.editor.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.ui.tools.ToolsActivity
import com.propdf.editor.utils.FileHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {

    enum class AnnotationType {
        FREEHAND, HIGHLIGHT, UNDERLINE, STRIKEOUT, ERASER,
        RECT, CIRCLE, ARROW, TEXT, STAMP, IMAGE, VLINE
    }

    data class Annotation(
        val id: String = UUID.randomUUID().toString(),
        val pageIndex: Int,
        val type: AnnotationType,
        val points: List<PointF>,
        val color: Int,
        val strokeWidth: Float,
        val alpha: Int = 255,
        val text: String? = null
    )

    inner class AnnotationManager {
        private val store    = mutableMapOf<Int, MutableList<Annotation>>()
        private val undoList = ArrayDeque<Annotation>()
        private val redoList = ArrayDeque<Annotation>()

        fun add(ann: Annotation) {
            store.getOrPut(ann.pageIndex) { mutableListOf() }.add(ann)
            undoList.addLast(ann); redoList.clear(); updateUndoRedoBtns()
        }
        fun get(page: Int): List<Annotation> = store[page] ?: emptyList()
        fun getAllPages(): Map<Int, List<Annotation>> = store
        fun hasAny() = store.values.any { it.isNotEmpty() }
        fun removeAnnotation(pageIndex: Int, ann: Annotation) { store[pageIndex]?.remove(ann) }
        fun undo() {
            val last = undoList.removeLastOrNull() ?: run { toast("Nothing to undo"); return }
            store[last.pageIndex]?.remove(last); redoList.addLast(last)
            updateUndoRedoBtns(); annotOverlays[last.pageIndex]?.invalidate()
        }
        fun redo() {
            val ann = redoList.removeLastOrNull() ?: run { toast("Nothing to redo"); return }
            store.getOrPut(ann.pageIndex) { mutableListOf() }.add(ann)
            undoList.addLast(ann); updateUndoRedoBtns(); annotOverlays[ann.pageIndex]?.invalidate()
        }
        fun undoCount() = undoList.size
        fun redoCount()  = redoList.size

        fun toJson(): String {
            val root = JSONObject(); val pages = JSONObject()
            store.forEach { (page, anns) ->
                val arr = JSONArray()
                anns.forEach { a ->
                    val obj = JSONObject()
                    obj.put("id", a.id); obj.put("type", a.type.name)
                    obj.put("color", a.color); obj.put("strokeWidth", a.strokeWidth)
                    obj.put("alpha", a.alpha)
                    if (a.text != null) obj.put("text", a.text)
                    val pts = JSONArray()
                    a.points.forEach { p -> pts.put(JSONObject().put("x", p.x).put("y", p.y)) }
                    obj.put("points", pts); arr.put(obj)
                }
                pages.put(page.toString(), arr)
            }
            root.put("pages", pages); root.put("version", 1); return root.toString()
        }

        fun fromJson(json: String) {
            try {
                store.clear(); undoList.clear(); redoList.clear()
                val root  = JSONObject(json); val pages = root.getJSONObject("pages")
                pages.keys().forEach { pageKey ->
                    val page = pageKey.toIntOrNull() ?: return@forEach
                    val arr  = pages.getJSONArray(pageKey); val list = mutableListOf<Annotation>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val ptsArr = obj.getJSONArray("points")
                        val pts = (0 until ptsArr.length()).map { j ->
                            val p = ptsArr.getJSONObject(j)
                            PointF(p.getDouble("x").toFloat(), p.getDouble("y").toFloat())
                        }
                        list.add(Annotation(id = obj.getString("id"), pageIndex = page,
                            type = AnnotationType.valueOf(obj.getString("type")),
                            points = pts, color = obj.getInt("color"),
                            strokeWidth = obj.getDouble("strokeWidth").toFloat(),
                            alpha = obj.optInt("alpha", 255),
                            text = if (obj.has("text")) obj.getString("text") else null))
                    }
                    store[page] = list
                }
                updateUndoRedoBtns()
            } catch (_: Exception) {}
        }
    }

    inner class AnnotationRenderer {
        fun render(canvas: Canvas, annotations: List<Annotation>,
                   livePoints: List<PointF> = emptyList(), liveTool: AnnotationType? = null,
                   liveColor: Int = Color.BLACK, liveWeight: Float = 5f) {
            val sc = canvas.saveLayer(null, null)
            annotations.forEach { ann -> drawAnnotation(canvas, ann) }
            if (liveTool != null && livePoints.size >= 2) {
                val paint = buildPaint(liveTool, liveColor, liveWeight, 255)
                canvas.drawPath(pointsToPath(liveTool, livePoints), paint)
            }
            canvas.restoreToCount(sc)
        }
        fun drawAnnotation(canvas: Canvas, ann: Annotation) {
            if (ann.points.isEmpty()) return
            when (ann.type) {
                AnnotationType.TEXT  -> drawText(canvas, ann)
                AnnotationType.STAMP -> drawStamp(canvas, ann)
                else -> canvas.drawPath(pointsToPath(ann.type, ann.points), buildPaint(ann.type, ann.color, ann.strokeWidth, ann.alpha))
            }
        }
        private fun pointsToPath(type: AnnotationType, points: List<PointF>): Path {
            val path = Path()
            when (type) {
                AnnotationType.FREEHAND, AnnotationType.HIGHLIGHT -> {
                    path.moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size - 1) {
                        val mx = (points[i].x + points[i + 1].x) / 2f
                        val my = (points[i].y + points[i + 1].y) / 2f
                        path.quadTo(points[i].x, points[i].y, mx, my)
                    }
                    if (points.size > 1) path.lineTo(points.last().x, points.last().y)
                }
                AnnotationType.UNDERLINE, AnnotationType.STRIKEOUT -> {
                    val yOff = if (type == AnnotationType.STRIKEOUT) 0f else dp(2).toFloat()
                    path.moveTo(points.first().x, points.first().y + yOff)
                    path.lineTo(points.last().x, points.first().y + yOff)
                }
                AnnotationType.VLINE -> {
                    if (points.size >= 2) { path.moveTo(points[0].x, points[0].y); path.lineTo(points[0].x, points.last().y) }
                }
                AnnotationType.RECT -> {
                    if (points.size >= 2) path.addRect(minOf(points[0].x,points[1].x),minOf(points[0].y,points[1].y),maxOf(points[0].x,points[1].x),maxOf(points[0].y,points[1].y),Path.Direction.CW)
                }
                AnnotationType.CIRCLE -> {
                    if (points.size >= 2) path.addOval(RectF(minOf(points[0].x,points[1].x),minOf(points[0].y,points[1].y),maxOf(points[0].x,points[1].x),maxOf(points[0].y,points[1].y)),Path.Direction.CW)
                }
                AnnotationType.ARROW -> {
                    if (points.size >= 2) {
                        val sx=points[0].x;val sy=points[0].y;val ex=points.last().x;val ey=points.last().y
                        path.moveTo(sx,sy);path.lineTo(ex,ey)
                        val angle=Math.atan2((ey-sy).toDouble(),(ex-sx).toDouble())
                        val aLen=dp(14).toFloat();val aAng=Math.PI/6
                        path.lineTo((ex-aLen*Math.cos(angle-aAng)).toFloat(),(ey-aLen*Math.sin(angle-aAng)).toFloat())
                        path.moveTo(ex,ey)
                        path.lineTo((ex-aLen*Math.cos(angle+aAng)).toFloat(),(ey-aLen*Math.sin(angle+aAng)).toFloat())
                    }
                }
                else -> { path.moveTo(points[0].x,points[0].y); points.drop(1).forEach { path.lineTo(it.x,it.y) } }
            }
            return path
        }
        fun buildPaint(type: AnnotationType, color: Int, weight: Float, alpha: Int): Paint {
            return Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color=color;this.alpha=alpha;strokeWidth=weight;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
                when (type) {
                    AnnotationType.HIGHLIGHT -> { style=Paint.Style.FILL_AND_STROKE;this.alpha=90;strokeWidth=weight*4 }
                    AnnotationType.UNDERLINE -> { style=Paint.Style.STROKE;strokeWidth=weight*1.5f;this.alpha=220 }
                    AnnotationType.STRIKEOUT -> { style=Paint.Style.STROKE;strokeWidth=weight*1.5f;this.alpha=220 }
                    AnnotationType.ERASER    -> { style=Paint.Style.STROKE;strokeWidth=weight*5f;xfermode=PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
                    else -> { style=Paint.Style.STROKE }
                }
            }
        }
        private fun drawText(canvas: Canvas, ann: Annotation) {
            if (ann.text.isNullOrEmpty() || ann.points.isEmpty()) return
            val rawText=ann.text;val parts=rawText.split("|",limit=2)
            val displayText=if(parts.size==2)parts[1] else rawText
            val fmtStr=if(parts.size==2)parts[0] else ""
            val fmtParts=fmtStr.split(":")
            val flags=if(fmtParts.isNotEmpty())fmtParts[0] else ""
            val align=if(fmtParts.size>1)fmtParts[1] else "left"
            val fontSize=if(fmtParts.size>2)fmtParts[2].toFloatOrNull()?:(ann.strokeWidth*3f) else ann.strokeWidth*3f
            val bold="B" in flags;val italic="I" in flags;val underline="U" in flags
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color=ann.color;textSize=fontSize.coerceIn(10f,72f);style=Paint.Style.FILL
                typeface=when{bold&&italic->Typeface.create(Typeface.DEFAULT,Typeface.BOLD_ITALIC);bold->Typeface.DEFAULT_BOLD;italic->Typeface.create(Typeface.DEFAULT,Typeface.ITALIC);else->Typeface.DEFAULT}
                isUnderlineText=underline
            }
            val bgColor=if(ann.color==Color.WHITE||ann.color==Color.YELLOW||ann.color==Color.parseColor("#FFFF00"))Color.argb(200,0,0,0) else Color.argb(200,255,255,200)
            val bg=Paint().apply{color=bgColor;style=Paint.Style.FILL}
            val fm=paint.fontMetrics;val tw=paint.measureText(displayText)
            val x=ann.points[0].x;val y=ann.points[0].y
            val ax=when(align){"center"->x-tw/2;"right"->x-tw;else->x}
            canvas.drawRoundRect(ax-dp(6),y+fm.top-dp(4),ax+tw+dp(6),y+fm.bottom+dp(4),dp(4).toFloat(),dp(4).toFloat(),bg)
            canvas.drawText(displayText,ax,y,paint)
        }
        private fun drawStamp(canvas: Canvas, ann: Annotation) {
            if (ann.text.isNullOrEmpty()||ann.points.isEmpty()) return
            val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.RED;alpha=180;textSize=dp(32).toFloat();style=Paint.Style.FILL;typeface=Typeface.DEFAULT_BOLD}
            val border=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.RED;alpha=180;style=Paint.Style.STROKE;strokeWidth=dp(2).toFloat()}
            val tw=paint.measureText(ann.text);val fm=paint.fontMetrics;val x=ann.points[0].x;val y=ann.points[0].y
            canvas.drawRect(x-dp(6),y+fm.top-dp(6),x+tw+dp(6),y+fm.bottom+dp(6),border)
            canvas.drawText(ann.text,x,y,paint)
        }
        private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    }

    inner class AnnotOverlay(context: Context, val pageIdx: Int) : View(context) {
        val livePoints=mutableListOf<PointF>()
        private var startX=0f;private var startY=0f
        private val renderer=AnnotationRenderer()
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val toolStr=activeTool?:return false
            if (toolStr in listOf("save","image","stamp","text","move_text","move_shape")) {
                if (ev.action==MotionEvent.ACTION_UP) handleSpecialToolUp(toolStr,ev.x,ev.y)
                return true
            }
            val type=toolStringToType(toolStr)?:return false
            val x=ev.x;val y=ev.y
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    livePoints.clear();livePoints.add(PointF(x,y));startX=x;startY=y
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                }
                MotionEvent.ACTION_MOVE -> {
                    when(type) {
                        AnnotationType.FREEHAND,AnnotationType.HIGHLIGHT -> livePoints.add(PointF(x,y))
                        AnnotationType.UNDERLINE,AnnotationType.STRIKEOUT,
                        AnnotationType.RECT,AnnotationType.CIRCLE,
                        AnnotationType.ARROW,AnnotationType.VLINE -> {
                            livePoints.clear();livePoints.add(PointF(startX,startY));livePoints.add(PointF(x,y))
                        }
                        else -> livePoints.add(PointF(x,y))
                    }
                    invalidate()
                }
                MotionEvent.ACTION_UP -> {
                    if (type==AnnotationType.ERASER) {
                        val bounds=RectF(x-dp(28).toFloat(),y-dp(28).toFloat(),x+dp(28).toFloat(),y+dp(28).toFloat())
                        val toRemove=annotationManager.get(pageIdx).filter{ann->ann.points.any{p->bounds.contains(p.x,p.y)}}
                        toRemove.forEach{annotationManager.removeAnnotation(pageIdx,it)}
                        livePoints.clear();invalidate();return true
                    }
                    val finalPts=ArrayList(livePoints)
                    if (finalPts.size<2){livePoints.clear();return true}
                    val col=if(type==AnnotationType.HIGHLIGHT)highlightColor else activeColor
                    annotationManager.add(Annotation(pageIndex=pageIdx,type=type,points=finalPts,color=col,strokeWidth=strokeWidth,alpha=if(type==AnnotationType.HIGHLIGHT)90 else 255))
                    livePoints.clear();invalidate()
                }
            }
            return true
        }
        private fun handleSpecialToolUp(tool:String,x:Float,y:Float) {
            if (tool!="move_text"&&tool!="move_shape") return
            val nearest=annotationManager.get(pageIdx).lastOrNull{ann->ann.points.any{p->Math.abs(p.x-x)<dp(40)&&Math.abs(p.y-y)<dp(40)}}
            if (nearest!=null) {
                val dx=x-(nearest.points.firstOrNull()?.x?:x);val dy=y-(nearest.points.firstOrNull()?.y?:y)
                val movedPts=nearest.points.map{PointF(it.x+dx,it.y+dy)}
                annotationManager.removeAnnotation(pageIdx,nearest);annotationManager.add(nearest.copy(points=movedPts))
                invalidate();toast("Annotation moved")
            } else toast("Tap near an annotation to move it")
        }
        override fun onDraw(canvas: Canvas) {
            renderer.render(canvas,annotationManager.get(pageIdx),livePoints,activeTool?.let{toolStringToType(it)},if(activeTool=="highlight")highlightColor else activeColor,strokeWidth)
        }
        private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    }

    private var pdfUri:Uri?=null;private var pdfPassword:String?=null;private var pdfFile:File?=null
    private var pdfRenderer:PdfRenderer?=null;private var currentPage=0;private var totalPages=0
    private var searchResultIdx=0;private var searchResults:List<Int>=emptyList();private var lastSearchQuery=""
    private val pageScaleMap=mutableMapOf<Int,Float>();private val pageTextCache=mutableMapOf<Int,String>()
    private val pageBitmapCache:LruCache<Int,Bitmap> by lazy {
        val maxKb=(Runtime.getRuntime().maxMemory()/1024L/8L).toInt().coerceAtLeast(8192)
        object:LruCache<Int,Bitmap>(maxKb){override fun sizeOf(key:Int,value:Bitmap)=value.byteCount/1024}
    }
    private var annotToolbarExpanded=false;private var activeAnnotGroup="markup"
    private var activeTool:String?=null;private var activeColor=Color.parseColor("#007AFF")
    private var highlightColor=Color.parseColor("#FFFF00");private var strokeWidth=5f
    private var textBold=false;private var textItalic=false;private var textUnderline=false
    private var textAlign="left";private var textFontSize=16f
    private val annotationManager=AnnotationManager();private val annotOverlays=mutableMapOf<Int,AnnotOverlay>()
    private val bookmarkedPages=mutableSetOf<Int>()
    private val COLOR_PALETTE=listOf("#FFFF00","#FF6B35","#E53935","#AD1457","#6A1B9A","#1565C0","#007AFF","#00897B","#2E7D32","#F9A825","#FF8F00","#4E342E","#FFFFFF","#9E9E9E","#000000","#00BCD4","#8BC34A","#FF4081","#AA00FF","#EF6719")
    private val ANNOT_GROUPS=linkedMapOf("markup" to listOf("freehand","highlight","underline","strikeout","eraser","vline"),"shapes" to listOf("rect","circle","arrow"),"inserts" to listOf("text","stamp","image"),"manage" to listOf("move_text","move_shape","save"))
    private val TOOL_LABEL=mapOf("freehand" to "Pen","highlight" to "High.","underline" to "Under.","strikeout" to "Strk","eraser" to "Erase","vline" to "VLine","rect" to "Rect","circle" to "Circle","arrow" to "Arrow","text" to "Text","stamp" to "Stamp","image" to "Image","move_text" to "MoveT","move_shape" to "MoveS","save" to "Save")
    private val TOOL_ICON=mapOf("freehand" to android.R.drawable.ic_menu_edit,"highlight" to android.R.drawable.ic_menu_view,"underline" to android.R.drawable.ic_menu_info_details,"strikeout" to android.R.drawable.ic_menu_delete,"eraser" to android.R.drawable.ic_menu_close_clear_cancel,"vline" to android.R.drawable.ic_media_pause,"rect" to android.R.drawable.ic_menu_crop,"circle" to android.R.drawable.ic_menu_search,"arrow" to android.R.drawable.ic_media_next,"text" to android.R.drawable.ic_dialog_info,"stamp" to android.R.drawable.ic_menu_send,"image" to android.R.drawable.ic_menu_gallery,"move_text" to android.R.drawable.ic_dialog_map,"move_shape" to android.R.drawable.ic_menu_compass,"save" to android.R.drawable.ic_menu_save)
    private fun toolStringToType(tool:String):AnnotationType?=when(tool){"freehand"->AnnotationType.FREEHAND;"highlight"->AnnotationType.HIGHLIGHT;"underline"->AnnotationType.UNDERLINE;"strikeout"->AnnotationType.STRIKEOUT;"eraser"->AnnotationType.ERASER;"vline"->AnnotationType.VLINE;"rect"->AnnotationType.RECT;"circle"->AnnotationType.CIRCLE;"arrow"->AnnotationType.ARROW;"text"->AnnotationType.TEXT;"stamp"->AnnotationType.STAMP;"image"->AnnotationType.IMAGE;else->null}
    private lateinit var rootLayout:LinearLayout;private lateinit var scrollView:ScrollView;private lateinit var pageContainer:LinearLayout
    private lateinit var searchBar:LinearLayout;private lateinit var pageCounter:TextView;private lateinit var searchInput:EditText
    private lateinit var searchCountLabel:TextView;private lateinit var annotSubMenuRow:LinearLayout;private lateinit var annotGroupNavBar:LinearLayout
    private lateinit var annotWeightValue:TextView;private lateinit var annotWeightBar:SeekBar;private lateinit var undoBtn:TextView;private lateinit var redoBtn:TextView
    private lateinit var annotFab:FrameLayout;private lateinit var annotPanel:LinearLayout
    private val annotSwatchViews=mutableListOf<View>()
    private val imagePicker=registerForActivityResult(ActivityResultContracts.GetContent()){uri:Uri?->
        uri?:return@registerForActivityResult
        lifecycleScope.launch {
            val bmp=withContext(Dispatchers.IO){try{contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it)}}catch(_:Exception){null}}
            if(bmp!=null){
                val scale=resources.displayMetrics.widthPixels.toFloat()/3f;val sx=resources.displayMetrics.widthPixels/2f-scale/2f
                val sy=(pageContainer.getChildAt(currentPage)?.height?.toFloat()?:400f)/3f
                annotationManager.add(Annotation(pageIndex=currentPage,type=AnnotationType.IMAGE,points=listOf(PointF(sx,sy),PointF(sx+scale,sy+scale*bmp.height/bmp.width.toFloat())),color=Color.WHITE,strokeWidth=scale,text=uri.toString()))
                annotOverlays[currentPage]?.invalidate();toast("Image inserted on page ${currentPage+1}")
            } else toast("Cannot load image")
        }
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val uriStr=intent.getStringExtra(EXTRA_URI);pdfUri=if(uriStr!=null)Uri.parse(uriStr) else intent.data;pdfPassword=intent.getStringExtra(EXTRA_PASSWORD)
        buildUI();loadPdf();loadAnnotationsFromCache()
    }
    override fun onDestroy(){super.onDestroy();closePdfRenderer();pageBitmapCache.evictAll()}
    override fun onBackPressed(){if(searchBar.visibility==View.VISIBLE){hideSearchBar();return};if(annotToolbarExpanded){collapseAnnotToolbar();return};super.onBackPressed()}

    private fun buildUI(){
        rootLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.parseColor("#121212"))}
        setContentView(rootLayout);rootLayout.addView(buildTopBar())
        searchBar=buildSearchBar();rootLayout.addView(searchBar)
        val navTint=Color.parseColor("#ADC6FF")
        val navStrip=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(Color.parseColor("#1A1A1A"));layoutParams=LinearLayout.LayoutParams(-1,-2)}
        navStrip.addView(ImageButton(this).apply{layoutParams=LinearLayout.LayoutParams(dp(44),dp(34));setImageResource(android.R.drawable.ic_media_previous);colorFilter=PorterDuffColorFilter(navTint,PorterDuff.Mode.SRC_IN);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{if(currentPage>0)scrollToPage(currentPage-1)}})
        pageCounter=TextView(this).apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f);gravity=Gravity.CENTER;setTextColor(navTint);textSize=11f;typeface=Typeface.DEFAULT_BOLD;setPadding(0,dp(3),0,dp(3));text="Loading...";setOnClickListener{showGoToPageDialog()}}
        navStrip.addView(pageCounter)
        navStrip.addView(ImageButton(this).apply{layoutParams=LinearLayout.LayoutParams(dp(44),dp(34));setImageResource(android.R.drawable.ic_media_next);colorFilter=PorterDuffColorFilter(navTint,PorterDuff.Mode.SRC_IN);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{if(currentPage<totalPages-1)scrollToPage(currentPage+1)}})
        rootLayout.addView(navStrip)
        scrollView=ScrollView(this).apply{layoutParams=LinearLayout.LayoutParams(-1,0,1f);setBackgroundColor(Color.parseColor("#282828"))}
        pageContainer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8))}
        scrollView.addView(pageContainer);rootLayout.addView(scrollView)
        scrollView.viewTreeObserver.addOnScrollChangedListener{updatePageCounterFromScroll()}
        rootLayout.addView(buildAnnotationArea());rootLayout.post{applyViewerTheme()}
    }
    private fun buildTopBar():LinearLayout{
        val cTxt=Color.parseColor("#ADC6FF")
        return LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL;setBackgroundColor(Color.parseColor("#1A1A1A"));gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4),dp(4),dp(4),dp(4));layoutParams=LinearLayout.LayoutParams(-1,-2)
            addView(buildIconBtn(android.R.drawable.ic_media_previous,"Back",cTxt){finish()})
            val intentName=intent.getStringExtra(EXTRA_DISPLAY_NAME)
            val rawName=intentName?:pdfUri?.let{FileHelper.getFileName(this@ViewerActivity,it)}?:pdfUri?.lastPathSegment?:"PDF"
            val dispName=rawName.removeSuffix(".pdf")
            addView(TextView(this@ViewerActivity).apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f);text=if(dispName.length>28)dispName.take(25)+"..." else dispName;setTextColor(cTxt);textSize=13f;typeface=Typeface.DEFAULT_BOLD;setPadding(dp(4),0,0,0)})
            addView(buildIconBtn(android.R.drawable.ic_menu_search,"Find",cTxt){toggleSearchBar()})
            addView(buildIconBtn(android.R.drawable.ic_menu_mapmode,"Mode",cTxt){showReadingModeDialog()})
            addView(buildIconBtn(android.R.drawable.ic_menu_edit,"OCR",cTxt){showOcrMenu()})
            addView(buildIconBtn(android.R.drawable.ic_menu_more,"More",cTxt){showPdfOpsMenu()})
        }
    }
    private fun buildIconBtn(iconRes:Int,desc:String,tint:Int,action:()->Unit):ImageButton{
        return ImageButton(this).apply{layoutParams=LinearLayout.LayoutParams(dp(34),dp(34));setImageResource(iconRes);colorFilter=PorterDuffColorFilter(tint,PorterDuff.Mode.SRC_IN);setBackgroundColor(Color.TRANSPARENT);contentDescription=desc;setOnClickListener{action()}}
    }
    private fun loadPdf(){
        lifecycleScope.launch{
            try{
                val uri=pdfUri?:run{toast("No PDF specified");return@launch}
                if(!isOpenableUri(uri))return@launch
                val file=withContext(Dispatchers.IO){copyUriToCache(uri)}
                if(file==null){toast("Cannot read PDF");return@launch}
                if(!file.exists()||!FileHelper.isPdf(file)){toast("Not a valid PDF");return@launch}
                pdfFile=file;pageTextCache.clear()
                val ok=withContext(Dispatchers.IO){try{openPdfRenderer(file);true}catch(_:Exception){false}}
                if(!ok){toast("Error opening PDF");return@launch}
                renderAllPages()
            }catch(_:kotlinx.coroutines.CancellationException){}
            catch(_:Exception){toast("Error opening PDF")}
        }
    }
    private fun isOpenableUri(uri:Uri):Boolean{
        val t=uri.toString().trim();if(t.isEmpty()){toast("Invalid file URI");return false}
        if(uri.scheme=="file"){val f=java.io.File(uri.path?:"");if(!f.exists()||!f.isFile){toast("File does not exist");return false};if(!FileHelper.isPdf(f)){toast("Not a valid PDF");return false};return true}
        if(!FileHelper.isValidPdfUri(this,uri)){toast("Cannot read PDF from URI");return false};return true
    }
    private fun copyUriToCache(uri:Uri):File?{
        return try{
            if(uri.scheme=="file"){val f=java.io.File(uri.path?:"");return if(f.exists()&&f.isFile)f else null}
            val dest=File(cacheDir,"viewer_${System.currentTimeMillis()}.pdf")
            contentResolver.openInputStream(uri)?.use{inp->FileOutputStream(dest).use{inp.copyTo(it)}}
            if(dest.length()>0)dest else null
        }catch(_:Exception){null}
    }
    private fun openPdfRenderer(file:File){closePdfRenderer();val pfd=ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);pdfRenderer=PdfRenderer(pfd);totalPages=pdfRenderer!!.pageCount}
    private fun closePdfRenderer(){try{pdfRenderer?.close()}catch(_:Exception){};pdfRenderer=null}
    private suspend fun renderAllPages(){
        val renderer=pdfRenderer?:return;val screenW=resources.displayMetrics.widthPixels-dp(16)
        totalPages=renderer.pageCount;pageScaleMap.clear();pageBitmapCache.evictAll()
        withContext(Dispatchers.Main){pageContainer.removeAllViews();annotOverlays.clear();pageCounter.text="Page 1 of $totalPages"}
        for(i in 0 until totalPages){val bmp=withContext(Dispatchers.IO){renderPageBitmap(i,screenW)}?:continue;withContext(Dispatchers.Main){addPageView(bmp,i)}}
        preloadNearbyPages(currentPage);withContext(Dispatchers.Main){applyViewerTheme();annotOverlays.values.forEach{it.invalidate()}}
    }
    private fun renderPageBitmap(pageIndex:Int,screenW:Int):Bitmap?{
        val cached=pageBitmapCache.get(pageIndex);if(cached!=null&&!cached.isRecycled)return cached
        val renderer=pdfRenderer?:return null
        synchronized(renderer){
            val page=renderer.openPage(pageIndex)
            return try{
                val scale=screenW.toFloat()/page.width.coerceAtLeast(1).toFloat()
                val bmpW=(page.width*scale).toInt().coerceAtLeast(1);val bmpH=(page.height*scale).toInt().coerceAtLeast(1)
                val b=Bitmap.createBitmap(bmpW,bmpH,Bitmap.Config.ARGB_8888);Canvas(b).drawColor(Color.WHITE)
                page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                pageScaleMap[pageIndex]=scale;pageBitmapCache.put(pageIndex,b);b
            }finally{page.close()}
        }
    }
    private fun preloadNearbyPages(anchorPage:Int){
        val screenW=resources.displayMetrics.widthPixels-dp(16)
        lifecycleScope.launch(Dispatchers.IO){val start=(anchorPage-2).coerceAtLeast(0);val end=(anchorPage+2).coerceAtMost(totalPages-1);for(idx in start..end){if(pageBitmapCache.get(idx)==null)renderPageBitmap(idx,screenW)}}
    }
    private fun addPageView(bmp:Bitmap,pageIndex:Int){
        val pageFrame=FrameLayout(this).apply{layoutParams=LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)}}
        pageFrame.addView(ZoomableImageView(this,bmp,pageIndex),FrameLayout.LayoutParams(-1,-2))
        val overlay=AnnotOverlay(this,pageIndex);annotOverlays[pageIndex]=overlay
        pageFrame.addView(overlay,FrameLayout.LayoutParams(-1,-1));pageContainer.addView(pageFrame)
    }
    private fun updatePageCounterFromScroll(){
        if(totalPages==0)return
        val page=((scrollView.scrollY.toFloat()/pageContainer.height.coerceAtLeast(1))*totalPages).toInt().coerceIn(0,totalPages-1)
        if(currentPage!=page){currentPage=page;preloadNearbyPages(currentPage)}
        val bm=if(bookmarkedPages.contains(page))" [B]" else ""
        pageCounter.text="Page ${page+1} of $totalPages$bm"
    }
    private fun updateUndoRedoBtns(){
        if(!::undoBtn.isInitialized)return
        val on=Color.parseColor("#ADC6FF");val off=Color.parseColor("#444444")
        undoBtn.setTextColor(if(annotationManager.undoCount()>0)on else off)
        redoBtn.setTextColor(if(annotationManager.redoCount()>0)on else off)
    }

    private fun toggleSearchBar(){if(searchBar.visibility==View.GONE){searchBar.visibility=View.VISIBLE;searchInput.requestFocus();(getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchInput,0)}else hideSearchBar()}
    private fun hideSearchBar(){
        searchBar.visibility=View.GONE;searchInput.setText("");searchResults=emptyList();searchResultIdx=0;lastSearchQuery="";updateSearchCounter();hideKeyboard()
        for(i in 0 until pageContainer.childCount){val f=pageContainer.getChildAt(i) as? FrameLayout?:continue;f.findViewWithTag<View>("search_highlight")?.let{f.removeView(it)};f.findViewWithTag<View>("search_highlight_region")?.let{f.removeView(it)}}
    }
    private fun buildSearchBar():LinearLayout{
        val cBg=Color.parseColor("#1E1E2E");val cInput=Color.parseColor("#0E0E1A");val cBlue=Color.parseColor("#ADC6FF");val cDark=Color.parseColor("#4B8EFF")
        return LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setBackgroundColor(cBg);setPadding(dp(12),dp(10),dp(12),dp(10));visibility=View.GONE
            val row1=LinearLayout(this@ViewerActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            val wrap=FrameLayout(this@ViewerActivity).apply{layoutParams=LinearLayout.LayoutParams(0,dp(44),1f).apply{marginEnd=dp(6)}}
            searchInput=EditText(this@ViewerActivity).apply{layoutParams=FrameLayout.LayoutParams(-1,-1);hint="Search in PDF...";setHintTextColor(Color.parseColor("#666888"));setTextColor(Color.WHITE);background=GradientDrawable().apply{setColor(cInput);cornerRadius=dp(10).toFloat()};setPadding(dp(12),0,dp(12),0);imeOptions=EditorInfo.IME_ACTION_SEARCH;setSingleLine(true);setOnEditorActionListener{_,actionId,_->if(actionId==EditorInfo.IME_ACTION_SEARCH){hideKeyboard();runSearch();true}else false}}
            wrap.addView(searchInput);row1.addView(wrap)
            row1.addView(TextView(this@ViewerActivity).apply{layoutParams=LinearLayout.LayoutParams(dp(60),dp(44));text="Find";setTextColor(Color.parseColor("#001A4D"));typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER;background=GradientDrawable().apply{colors=intArrayOf(cBlue,cDark);gradientType=GradientDrawable.LINEAR_GRADIENT;orientation=GradientDrawable.Orientation.TL_BR;cornerRadius=dp(10).toFloat()};setOnClickListener{hideKeyboard();runSearch()}})
            row1.addView(ImageButton(this@ViewerActivity).apply{layoutParams=LinearLayout.LayoutParams(dp(40),dp(44)).apply{marginStart=dp(4)};setImageResource(android.R.drawable.ic_menu_close_clear_cancel);colorFilter=PorterDuffColorFilter(Color.parseColor("#FF4444"),PorterDuff.Mode.SRC_IN);setBackgroundColor(Color.TRANSPARENT);setOnClickListener{hideSearchBar()}})
            addView(row1);addView(View(this@ViewerActivity).apply{layoutParams=LinearLayout.LayoutParams(-1,dp(6))})
            val row2=LinearLayout(this@ViewerActivity).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
            row2.addView(buildSearchNavBtn("< Prev"){if(searchResults.isNotEmpty()){searchResultIdx=(searchResultIdx-1+searchResults.size)%searchResults.size;scrollToPage(searchResults[searchResultIdx]);updateSearchCounter()}})
            searchCountLabel=TextView(this@ViewerActivity).apply{layoutParams=LinearLayout.LayoutParams(0,-2,1f);gravity=Gravity.CENTER;textSize=12f;setTextColor(cBlue);text="Tap Find"}
            row2.addView(searchCountLabel)
            row2.addView(buildSearchNavBtn("Next >"){if(searchResults.isNotEmpty()){searchResultIdx=(searchResultIdx+1)%searchResults.size;scrollToPage(searchResults[searchResultIdx]);updateSearchCounter()}})
            row2.addView(TextView(this@ViewerActivity).apply{layoutParams=LinearLayout.LayoutParams(-2,dp(36)).apply{marginStart=dp(8)};text="Go to Pg";textSize=10f;typeface=Typeface.DEFAULT_BOLD;setTextColor(cBlue);gravity=Gravity.CENTER;setPadding(dp(10),dp(6),dp(10),dp(6));background=GradientDrawable().apply{setColor(Color.parseColor("#2D2D44"));cornerRadius=dp(8).toFloat()};setOnClickListener{showGoToPageDialog()}})
            addView(row2)
        }
    }
    private fun buildSearchNavBtn(label:String,action:()->Unit):TextView{
        return TextView(this).apply{layoutParams=LinearLayout.LayoutParams(-2,dp(36)).apply{if(label.startsWith("<"))marginEnd=dp(6) else marginStart=dp(6)};text=label;textSize=11f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.parseColor("#E5E2E1"));gravity=Gravity.CENTER;setPadding(dp(14),0,dp(14),0);background=GradientDrawable().apply{setColor(Color.parseColor("#2D2D2D"));cornerRadius=dp(10).toFloat()};setOnClickListener{action()}}
    }
    private fun runSearch(){
        val query=searchInput.text.toString().trim();if(query.isEmpty()){toast("Enter search text");return}
        lastSearchQuery=query;if(::searchCountLabel.isInitialized)searchCountLabel.text="Searching..."
        lifecycleScope.launch{
            val results=withContext(Dispatchers.IO){val q=query.lowercase(java.util.Locale.getDefault());(0 until totalPages).filter{idx->extractPageText(idx).lowercase(java.util.Locale.getDefault()).contains(q)}}
            searchResults=results;searchResultIdx=0;updateSearchCounter()
            if(searchResults.isNotEmpty()){scrollToPage(searchResults[0]);highlightSearchOnPage(searchResults[0],query)}
            else toast("No matches found for "$query"")
        }
    }
    private fun extractPageText(pageIndex:Int):String{
        pageTextCache[pageIndex]?.let{return it};val file=pdfFile?:return ""
        return try{val doc=com.tom_roush.pdfbox.pdmodel.PDDocument.load(file);try{val s=com.tom_roush.pdfbox.text.PDFTextStripper();s.startPage=pageIndex+1;s.endPage=pageIndex+1;val t=s.getText(doc).trim();pageTextCache[pageIndex]=t;t}finally{doc.close()}}catch(_:Exception){""}
    }
    private fun highlightSearchOnPage(page:Int,query:String){
        val frame=pageContainer.getChildAt(page) as? FrameLayout?:return
        frame.findViewWithTag<View>("search_highlight")?.let{frame.removeView(it)};frame.findViewWithTag<View>("search_highlight_region")?.let{frame.removeView(it)}
        val banner=TextView(this).apply{tag="search_highlight";text="Find: "$query"";textSize=11f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.parseColor("#001A4D"));setPadding(dp(12),dp(6),dp(12),dp(6));background=GradientDrawable().apply{setColor(Color.parseColor("#FFFF00"));cornerRadius=dp(6).toFloat()};layoutParams=FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,FrameLayout.LayoutParams.WRAP_CONTENT,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(8)}}
        frame.addView(banner)
        val region=View(this).apply{tag="search_highlight_region";layoutParams=FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,dp(40),Gravity.TOP).apply{topMargin=dp(56);marginStart=dp(8);marginEnd=dp(8)};background=GradientDrawable().apply{setColor(Color.argb(90,255,245,120));setStroke(dp(1),Color.parseColor("#FFD600"));cornerRadius=dp(6).toFloat()}}
        frame.addView(region);frame.postDelayed({frame.removeView(banner);frame.removeView(region)},4000L)
    }
    private fun updateSearchCounter(){if(!::searchCountLabel.isInitialized)return;searchCountLabel.text=if(searchResults.isEmpty())"No matches" else "${searchResultIdx+1} / ${searchResults.size} pages"}
    private fun scrollToPage(page:Int){
        if(pageContainer.childCount<=page)return
        for(i in 0 until pageContainer.childCount){val f=pageContainer.getChildAt(i) as? FrameLayout?:continue;f.findViewWithTag<View>("search_highlight")?.let{f.removeView(it)};f.findViewWithTag<View>("search_highlight_region")?.let{f.removeView(it)}}
        scrollView.post{scrollView.post{val child=pageContainer.getChildAt(page)?:return@post;scrollView.scrollTo(0,child.top);currentPage=page;val bm=if(bookmarkedPages.contains(page))" [B]" else "";pageCounter.text="Page ${page+1} of $totalPages$bm";if(lastSearchQuery.isNotEmpty())highlightSearchOnPage(page,lastSearchQuery)}}
    }
    private fun showGoToPageDialog(){
        val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),dp(10))}
        val input=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;hint="Enter page (1 - $totalPages)";textSize=16f;setPadding(dp(8),dp(8),dp(8),dp(8));setSelectAllOnFocus(true)}
        val info=TextView(this).apply{text="Currently on page ${currentPage+1} of $totalPages";textSize=12f;setTextColor(Color.parseColor("#8B90A0"));setPadding(0,dp(6),0,0)}
        container.addView(input);container.addView(info)
        val jumpRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(0,dp(8),0,0)}
        listOf("First" to 1,"Prev" to currentPage,"Next" to (currentPage+2),"Last" to totalPages).forEach{(label,pg)->
            jumpRow.addView(TextView(this).apply{text=label;textSize=11f;typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER;setPadding(dp(12),dp(6),dp(12),dp(6));layoutParams=LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(6)};setTextColor(Color.parseColor("#ADC6FF"));background=GradientDrawable().apply{setColor(Color.parseColor("#2D2D2D"));cornerRadius=dp(8).toFloat()};setOnClickListener{scrollToPage(pg.coerceIn(1,totalPages)-1)}})
        }
        container.addView(jumpRow)
        AlertDialog.Builder(this).setTitle("Navigate to Page").setView(container).setPositiveButton("Go"){_,_->val pg=input.text.toString().toIntOrNull();if(pg!=null&&pg in 1..totalPages)scrollToPage(pg-1) else toast("Enter a page between 1 and $totalPages")}.setNegativeButton("Cancel",null).show()
    }
    private fun showReadingModeDialog(){
        AlertDialog.Builder(this).setTitle("View & Reading Mode").setItems(arrayOf("Normal","Night Mode","Sepia","Day (Bright)","Fit to Width","Fit Full Page","Reset All")){_,which->
            when(which){0->applyReadingFilter("normal");1->applyReadingFilter("night");2->applyReadingFilter("sepia");3->applyReadingFilter("day");4->forEachZoomView{it.fitToWidth()};5->forEachZoomView{it.fitToPage()};6->{applyReadingFilter("normal");forEachZoomView{it.resetTransform()}}}
        }.show()
    }
    private fun forEachZoomView(block:(ZoomableImageView)->Unit){for(i in 0 until pageContainer.childCount){val f=pageContainer.getChildAt(i) as? FrameLayout?:continue;(f.getChildAt(0) as? ZoomableImageView)?.let(block)}}
    private fun applyReadingFilter(mode:String){
        val matrix=android.graphics.ColorMatrix()
        when(mode){"night"->matrix.set(floatArrayOf(-1f,0f,0f,0f,255f,0f,-1f,0f,0f,255f,0f,0f,-1f,0f,255f,0f,0f,0f,1f,0f));"sepia"->matrix.set(floatArrayOf(0.393f,0.769f,0.189f,0f,0f,0.349f,0.686f,0.168f,0f,0f,0.272f,0.534f,0.131f,0f,0f,0f,0f,0f,1f,0f));"day"->matrix.set(floatArrayOf(1.2f,0f,0f,0f,20f,0f,1.2f,0f,0f,20f,0f,0f,1.2f,0f,20f,0f,0f,0f,1f,0f));else->{forEachZoomView{it.applyFilter(null)};return}}
        val filter=android.graphics.ColorMatrixColorFilter(matrix);forEachZoomView{it.applyFilter(filter)}
    }
    private val viewerPrefs by lazy{getSharedPreferences("propdf_prefs",Context.MODE_PRIVATE)}
    private fun applyViewerTheme(){val dark=viewerPrefs.getBoolean("dark_mode",true);val bg=if(dark)Color.parseColor("#121212") else Color.WHITE;val pageBg=if(dark)Color.parseColor("#282828") else Color.parseColor("#F0F0F0");rootLayout.setBackgroundColor(bg);scrollView.setBackgroundColor(pageBg);pageContainer.setBackgroundColor(pageBg)}

    private fun showOcrMenu(){
        AlertDialog.Builder(this).setTitle("Text & OCR").setItems(arrayOf("Extract text -- this page","Extract text -- all pages","Copy page text","Find & navigate","Select & copy","OCR image PDF (ML Kit)")){_,which->
            when(which){0->extractAndShowPageText();1->extractAndShowAllText();2->copyPageTextDirect();3->toggleSearchBar();4->toast("Select & Copy: overlays selectable EditText over page");5->runMlKitOcrOnPage()}
        }.show()
    }
    private fun extractAndShowPageText(){lifecycleScope.launch{val text=withContext(Dispatchers.IO){extractPageText(currentPage)};showTextDialog("Page ${currentPage+1} Text",text.ifBlank{"No text found. Try OCR for image-based PDFs."})}}
    private fun extractAndShowAllText(){
        lifecycleScope.launch{toast("Extracting text from $totalPages pages...");val text=withContext(Dispatchers.IO){val sb=StringBuilder();for(i in 0 until totalPages){val t=extractPageText(i);if(t.isNotBlank()){sb.append("--- Page ${i+1} ---
");sb.append(t);sb.append("

")}};sb.toString().trim()};showTextDialog("Full Document Text",text.ifBlank{"No extractable text found."})}
    }
    private fun copyPageTextDirect(){lifecycleScope.launch{val text=withContext(Dispatchers.IO){extractPageText(currentPage)};val cb=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cb.setPrimaryClip(ClipData.newPlainText("PDF Page Text",text.ifBlank{"No text on page ${currentPage+1}"}));toast("Page ${currentPage+1} text copied")}}
    private fun runMlKitOcrOnPage(){val frame=pageContainer.getChildAt(currentPage) as? FrameLayout?:return;val zoom=frame.getChildAt(0) as? ZoomableImageView?:return;val bmp=zoom.getRenderedBitmap()?:return;toast("OCR running on page ${currentPage+1}...");val info="ML Kit OCR on page ${currentPage+1}.

Full impl:
  val image = InputImage.fromBitmap(bmp, 0)
  TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    .process(image).addOnSuccessListener { showTextDialog(it.text) }";showTextDialog("ML Kit OCR",info)}
    private fun showTextDialog(title:String,text:String){val et=EditText(this).apply{setText(text);textSize=13f;setTextColor(Color.parseColor("#E5E2E1"));setBackgroundColor(Color.parseColor("#1A1A1A"));setPadding(dp(16),dp(8),dp(16),dp(8));setTextIsSelectable(true)};AlertDialog.Builder(this).setTitle(title).setView(ScrollView(this).apply{addView(et)}).setPositiveButton("Copy"){_,_->val cb=getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager;cb.setPrimaryClip(ClipData.newPlainText("PDF",text));toast("Copied")}.setNegativeButton("Close",null).show()}
    private fun showPdfOpsMenu(){
        val bm=if(bookmarkedPages.contains(currentPage))"Remove Bookmark" else "Bookmark This Page"
        AlertDialog.Builder(this).setTitle("PDF Operations").setItems(arrayOf(bm,"All Bookmarks","Go to Page","Add Watermark","Rotate Page","Delete Page","Compress PDF","Page to Image","Image to PDF","Merge / Split","Share PDF","Open Tools")){_,which->
            when(which){0->toggleBookmark();1->showBookmarksDialog();2->showGoToPageDialog();3->toast("Watermark: via PdfOperationsManager.addWatermark()");4->showRotatePageDialog();5->toast("Delete page: via PdfOperationsManager.deletePage()");6->showCompressPdfDialog();7->showPageToImageDialog();8->showImageToPdfDialog();9->startActivity(Intent(this,ToolsActivity::class.java));10->sharePdf();11->startActivity(Intent(this,ToolsActivity::class.java))}
        }.show()
    }
    private fun toggleBookmark(){
        val bPrefs=getSharedPreferences("propdf_bookmarks",Context.MODE_PRIVATE);val key=pdfUri?.toString()?.hashCode().toString()
        val existing=bPrefs.getStringSet(key,mutableSetOf())!!.toMutableSet()
        if(bookmarkedPages.contains(currentPage)){bookmarkedPages.remove(currentPage);existing.removeAll{it.startsWith("$currentPage:")||it==currentPage.toString()};toast("Bookmark removed")}
        else{bookmarkedPages.add(currentPage);val fname=pdfUri?.let{FileHelper.getFileName(this,it))?:"document.pdf";existing.add("$currentPage:$fname");toast("Page ${currentPage+1} bookmarked")}
        bPrefs.edit().putStringSet(key,existing).apply();updatePageCounterFromScroll()
    }
    private fun showBookmarksDialog(){
        if(bookmarkedPages.isEmpty()){toast("No bookmarks -- use More > Bookmark This Page");return}
        val bPrefs=getSharedPreferences("propdf_bookmarks",Context.MODE_PRIVATE);val key=pdfUri?.toString()?.hashCode().toString()
        val raw=bPrefs.getStringSet(key,emptySet())?:emptySet()
        data class BM(val page:Int,val label:String)
        val entries=raw.mapNotNull{entry->val parts=entry.split(":",limit=2);val pg=parts[0].toIntOrNull()?:return@mapNotNull null;BM(pg,if(parts.size>1)parts[1] else "Page ${pg+1}")}.sortedBy{it.page}
        if(entries.isEmpty()){toast("No bookmarks yet");return}
        AlertDialog.Builder(this).setTitle("Bookmarks (${entries.size})").setItems(entries.map{"Page ${it.page+1} -- ${it.label}"}.toTypedArray()){_,i->scrollToPage(entries[i].page)}
            .setNeutralButton("Add Label"){_,_->
                val et=EditText(this).apply{hint="Label for page ${currentPage+1}"}
                AlertDialog.Builder(this).setTitle("Bookmark Label").setView(et).setPositiveButton("Save"){_,_->
                    val lbl=et.text.toString().trim();if(!bookmarkedPages.contains(currentPage))toggleBookmark()
                    val k2=pdfUri?.toString()?.hashCode().toString();val set2=bPrefs.getStringSet(k2,mutableSetOf())!!.toMutableSet()
                    set2.removeAll{it.startsWith("$currentPage:")};set2.add("$currentPage:$lbl");bPrefs.edit().putStringSet(k2,set2).apply();toast("Labelled: $lbl")
                }.setNegativeButton("Cancel",null).show()
            }.show()
    }
    private fun showRotatePageDialog(){
        AlertDialog.Builder(this).setTitle("Rotate Page ${currentPage+1}").setItems(arrayOf("90 degrees clockwise","90 degrees counter-clockwise","180 degrees","Custom angle...")){_,which->
            when(which){0->applyPageRotation(90f);1->applyPageRotation(-90f);2->applyPageRotation(180f);3->{val et=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;hint="Enter degrees (e.g. 45)";setPadding(dp(16),dp(8),dp(16),dp(8))};AlertDialog.Builder(this).setTitle("Custom Rotation").setView(et).setPositiveButton("Rotate"){_,_->val deg=et.text.toString().toFloatOrNull();if(deg!=null)applyPageRotation(deg) else toast("Enter a valid number")}.setNegativeButton("Cancel",null).show()}}
        }.show()
    }
    private fun applyPageRotation(degrees:Float){val frame=pageContainer.getChildAt(currentPage) as? FrameLayout?:return;val zoom=frame.getChildAt(0) as? ZoomableImageView?:return;val bmp=zoom.getRenderedBitmap()?:return;val matrix=Matrix().apply{postRotate(degrees)};val rotated=Bitmap.createBitmap(bmp,0,0,bmp.width,bmp.height,matrix,true);pageContainer.removeViewAt(currentPage);addPageView(rotated,currentPage);toast("Page rotated ${degrees.toInt()} degrees")}
    private fun showCompressPdfDialog(){
        val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),dp(10))}
        val origSize=pdfFile?.length()?:0L
        container.addView(TextView(this).apply{text="Current size: ${formatSize(origSize)}";textSize=13f;setTextColor(Color.WHITE)})
        val estimateLabel=TextView(this).apply{textSize=13f;typeface=Typeface.DEFAULT_BOLD;setTextColor(Color.parseColor("#ADC6FF"))}
        val pctLabel=TextView(this).apply{textSize=11f;gravity=Gravity.CENTER;setTextColor(Color.WHITE)}
        container.addView(estimateLabel);container.addView(pctLabel)
        val slider=SeekBar(this).apply{max=90;progress=40;layoutParams=LinearLayout.LayoutParams(-1,-2).apply{setMargins(0,dp(8),0,dp(8))}}
        fun updateEst(p:Int){val t=p+10;val est=(origSize*(100-t)/100L).coerceAtLeast(1024L);pctLabel.text="Reduce by ~$t%";estimateLabel.text="Estimated: ${formatSize(est)}"}
        updateEst(slider.progress);slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(sb:SeekBar?,p:Int,f:Boolean){updateEst(p)};override fun onStartTrackingTouch(sb:SeekBar?){}; override fun onStopTrackingTouch(sb:SeekBar?){}})
        container.addView(slider)
        AlertDialog.Builder(this).setTitle("Compress PDF").setView(container).setPositiveButton("Compress"){_,_->toast("Compress: impl via PdfOperationsManager.compressPdf(pdfFile,quality=${slider.progress+10})")}.setNegativeButton("Cancel",null).show()
    }
    private fun showPageToImageDialog(){
        AlertDialog.Builder(this).setTitle("Export Page ${currentPage+1} as Image").setItems(arrayOf("PNG (lossless)","JPEG High (95%)","JPEG Medium (80%)","JPEG Compressed (60%)")){_,which->
            val frame=pageContainer.getChildAt(currentPage) as? FrameLayout?:return@setItems;val zoom=frame.getChildAt(0) as? ZoomableImageView?:return@setItems;val bmp=zoom.getRenderedBitmap()?:run{toast("No page bitmap");return@setItems}
            lifecycleScope.launch{val saved=withContext(Dispatchers.IO){try{val ext=if(which==0)"png" else "jpg";val mime=if(which==0)"image/png" else "image/jpeg";val quality=when(which){0->100;1->95;2->80;else->60};val format=if(which==0)Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG;val fileName="page_${currentPage+1}_${System.currentTimeMillis()}.$ext";if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,fileName);put(MediaStore.MediaColumns.MIME_TYPE,mime);put(MediaStore.MediaColumns.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/ProPDF")};val uri=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(uri!=null)contentResolver.openOutputStream(uri)?.use{bmp.compress(format,quality,it)}}else{val dir=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"ProPDF").also{it.mkdirs()};FileOutputStream(File(dir,fileName)).use{bmp.compress(format,quality,it)}};true}catch(_:Exception){false}};toast(if(saved)"Image saved to Downloads/ProPDF" else "Export failed")}
        }.show()
    }
    private fun showImageToPdfDialog(){val pageSizes=arrayOf("Original size","A4 (595x842 pt)","A3 (842x1191 pt)","Letter (612x792 pt)","Legal (612x1008 pt)");val dims=listOf<Pair<Float,Float>?>(null,595f to 842f,842f to 1191f,612f to 792f,612f to 1008f);AlertDialog.Builder(this).setTitle("Image to PDF").setMessage("Pick an image from gallery, then convert it to PDF.").setItems(pageSizes){_,which->val dim=dims[which];toast("Image to PDF with size: ${dim?.let{"${it.first}x${it.second}pt"}?:"original"}");imagePicker.launch("image/*")}.show()}
    private fun sharePdf(){val file=pdfFile?:return;try{val uri=androidx.core.content.FileProvider.getUriForFile(this,"$packageName.provider",file);startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply{type="application/pdf";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)},"Share PDF"))}catch(_:Exception){toast("Cannot share")}}

    private fun buildAnnotationArea():FrameLayout{
        val outer=FrameLayout(this).apply{layoutParams=LinearLayout.LayoutParams(-1,-2)}
        annotPanel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.parseColor("#131313"));layoutParams=FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);visibility=View.GONE}
        annotPanel.addView(buildSettingsPill())
        val toolScroll=HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;setPadding(dp(6),dp(4),dp(6),dp(4))}
        annotSubMenuRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL};toolScroll.addView(annotSubMenuRow);annotPanel.addView(toolScroll)
        annotGroupNavBar=buildAnnotGroupNav();annotPanel.addView(annotGroupNavBar)
        val quickStrip=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setBackgroundColor(Color.parseColor("#1A1A1A"));setPadding(dp(12),dp(6),dp(12),dp(6))}
        undoBtn=buildQuickBtn("???");undoBtn.setOnClickListener{annotationManager.undo()}
        redoBtn=buildQuickBtn("???");redoBtn.setOnClickListener{annotationManager.redo()}
        val saveBtn=buildQuickBtn("Save",Color.parseColor("#2E7D32"),Color.parseColor("#1B3A1C"));saveBtn.setOnClickListener{saveAnnotations()}
        val closeBtn=buildQuickBtn("Close",Color.parseColor("#888888"),Color.parseColor("#2D2D2D"));closeBtn.setOnClickListener{collapseAnnotToolbar()}
        quickStrip.addView(undoBtn);quickStrip.addView(redoBtn);quickStrip.addView(View(this).apply{layoutParams=LinearLayout.LayoutParams(0,1,1f)});quickStrip.addView(saveBtn);quickStrip.addView(closeBtn)
        annotPanel.addView(quickStrip);outer.addView(annotPanel)
        annotFab=FrameLayout(this).apply{val s=dp(52);layoutParams=FrameLayout.LayoutParams(s,s).apply{gravity=Gravity.BOTTOM or Gravity.END;bottomMargin=dp(16);marginEnd=dp(16)};setBackgroundColor(Color.parseColor("#007AFF"));outlineProvider=object:ViewOutlineProvider(){override fun getOutline(v:View,o:android.graphics.Outline){o.setOval(0,0,v.width,v.height)}};clipToOutline=true;elevation=dp(12).toFloat();setOnClickListener{toggleAnnotToolbar()}}
        annotFab.addView(ImageView(this).apply{setImageResource(android.R.drawable.ic_menu_edit);setColorFilter(Color.WHITE);layoutParams=FrameLayout.LayoutParams(dp(24),dp(24)).apply{gravity=Gravity.CENTER}})
        outer.addView(annotFab);refreshAnnotSubMenu("markup");return outer
    }
    private fun buildQuickBtn(label:String,textColor:Int=Color.parseColor("#ADC6FF"),bgColor:Int=Color.parseColor("#2D2D2D")):TextView{
        return TextView(this).apply{layoutParams=LinearLayout.LayoutParams(-2,dp(32)).apply{marginStart=dp(6)};text=label;textSize=if(label.length==1)14f else 11f;typeface=Typeface.DEFAULT_BOLD;setTextColor(textColor);setPadding(dp(10),0,dp(10),0);gravity=Gravity.CENTER;background=GradientDrawable().apply{setColor(bgColor);cornerRadius=dp(8).toFloat()}}
    }
    private fun toggleAnnotToolbar(){if(annotToolbarExpanded)collapseAnnotToolbar() else expandAnnotToolbar()}
    private fun expandAnnotToolbar(){annotToolbarExpanded=true;annotPanel.visibility=View.VISIBLE;annotFab.setBackgroundColor(Color.parseColor("#FF6B35"))}
    private fun collapseAnnotToolbar(){annotToolbarExpanded=false;annotPanel.visibility=View.GONE;activeTool=null;annotFab.setBackgroundColor(Color.parseColor("#007AFF"));refreshAnnotSubMenu(activeAnnotGroup)}
    private fun buildSettingsPill():FrameLayout{
        val cPill=Color.parseColor("#1A1A1A");val cDim=Color.parseColor("#2D2D2D");val cTxt=Color.parseColor("#8B90A0");val cBlue=Color.parseColor("#ADC6FF")
        val pill=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(6),dp(10),dp(6));background=GradientDrawable().apply{setColor(cPill);cornerRadius=dp(24).toFloat()};elevation=dp(3).toFloat()}
        pill.addView(TextView(this).apply{text="W";textSize=9f;typeface=Typeface.DEFAULT_BOLD;setTextColor(cTxt)})
        annotWeightValue=TextView(this).apply{text=strokeWidth.toInt().toString();textSize=10f;typeface=Typeface.DEFAULT_BOLD;setTextColor(cBlue);setPadding(dp(3),0,dp(3),0)}
        annotWeightValue.setOnClickListener{showStrokeWidthInputDialog()};pill.addView(annotWeightValue)
        annotWeightBar=SeekBar(this).apply{layoutParams=LinearLayout.LayoutParams(dp(70),dp(22));max=48;progress=(strokeWidth.toInt()-2).coerceIn(0,48);setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{override fun onProgressChanged(sb:SeekBar?,p:Int,fromUser:Boolean){if(!fromUser)return;strokeWidth=(p+2).toFloat();annotWeightValue.text=(p+2).toString()};override fun onStartTrackingTouch(sb:SeekBar?){};override fun onStopTrackingTouch(sb:SeekBar?){}})}
        pill.addView(annotWeightBar);pill.addView(View(this).apply{layoutParams=LinearLayout.LayoutParams(dp(1),dp(18)).apply{setMargins(dp(6),0,dp(6),0)};setBackgroundColor(cDim)})
        val cs=HorizontalScrollView(this).apply{layoutParams=LinearLayout.LayoutParams(dp(130),dp(26));isHorizontalScrollBarEnabled=false}
        val cr=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(2),0,dp(2),0)}
        annotSwatchViews.clear()
        COLOR_PALETTE.forEach{hex->val col=Color.parseColor(hex);val sw=View(this).apply{val sz=dp(18);layoutParams=LinearLayout.LayoutParams(sz,sz).apply{setMargins(dp(2),0,dp(2),0)};tag=col;applySwatchStyle(this,col,col==activeColor);setOnClickListener{activeColor=col;if(activeTool=="highlight")highlightColor=col;annotSwatchViews.forEach{sv->applySwatchStyle(sv,sv.tag as Int,sv.tag as Int==activeColor)}};setOnLongClickListener{highlightColor=col;activeColor=col;annotSwatchViews.forEach{sv->applySwatchStyle(sv,sv.tag as Int,sv.tag as Int==activeColor)};toast("Highlight colour set");true}};annotSwatchViews.add(sw);cr.addView(sw)}
        cs.addView(cr);pill.addView(cs)
        return FrameLayout(this).apply{layoutParams=LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6);bottomMargin=dp(4)};addView(pill,FrameLayout.LayoutParams(-2,-2,Gravity.CENTER_HORIZONTAL))}
    }
    private fun showStrokeWidthInputDialog(){val input=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(strokeWidth.toInt().toString());setSelectAllOnFocus(true)};AlertDialog.Builder(this).setTitle("Stroke width (2-50)").setView(input).setPositiveButton("Apply"){_,_->val v=input.text.toString().toIntOrNull()?.coerceIn(2,50)?:return@setPositiveButton;strokeWidth=v.toFloat();if(::annotWeightValue.isInitialized)annotWeightValue.text=v.toString();if(::annotWeightBar.isInitialized)annotWeightBar.progress=v-2}.setNegativeButton("Cancel",null).show()}
    private fun applySwatchStyle(view:View,color:Int,isActive:Boolean){view.background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(color);if(isActive)setStroke(dp(2),Color.parseColor("#ADC6FF"))};view.alpha=if(isActive)1f else 0.8f}
    private fun buildAnnotGroupNav():LinearLayout{
        val cActive=Color.parseColor("#ADC6FF");val cInact=Color.parseColor("#8B90A0")
        data class GDef(val id:String,val icon:Int,val label:String)
        val groups=listOf(GDef("markup",android.R.drawable.ic_menu_edit,"MARKUP"),GDef("shapes",android.R.drawable.ic_menu_crop,"SHAPES"),GDef("inserts",android.R.drawable.ic_menu_add,"INSERTS"),GDef("manage",android.R.drawable.ic_menu_agenda,"MANAGE"))
        return LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setBackgroundColor(Color.parseColor("#1A1A1A"));layoutParams=LinearLayout.LayoutParams(-1,dp(50))
            groups.forEach{g->val isActive=g.id==activeAnnotGroup;addView(LinearLayout(this@ViewerActivity).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(0,-1,1f);if(isActive)background=GradientDrawable().apply{colors=intArrayOf(Color.parseColor("#1A1A1A"),Color.parseColor("#2D2D2D"));gradientType=GradientDrawable.LINEAR_GRADIENT;orientation=GradientDrawable.Orientation.TL_BR;cornerRadius=dp(8).toFloat()};setOnClickListener{activeAnnotGroup=g.id;rebuildAnnotGroupNav();refreshAnnotSubMenu(g.id)};addView(ImageView(this@ViewerActivity).apply{layoutParams=LinearLayout.LayoutParams(dp(16),dp(16));setImageResource(g.icon);colorFilter=PorterDuffColorFilter(if(isActive)cActive else cInact,PorterDuff.Mode.SRC_IN);alpha=if(isActive)1f else 0.65f});addView(TextView(this@ViewerActivity).apply{text=g.label;textSize=8f;typeface=Typeface.DEFAULT_BOLD;setTextColor(if(isActive)cActive else cInact);alpha=if(isActive)1f else 0.65f;gravity=Gravity.CENTER;setPadding(0,dp(2),0,0)})})}
        }
    }
    private fun rebuildAnnotGroupNav(){if(!::annotGroupNavBar.isInitialized)return;val parent=annotGroupNavBar.parent as? LinearLayout?:return;val idx=(0 until parent.childCount).indexOfFirst{parent.getChildAt(it)===annotGroupNavBar};if(idx<0)return;parent.removeViewAt(idx);annotGroupNavBar=buildAnnotGroupNav();parent.addView(annotGroupNavBar,idx)}
    private fun refreshAnnotSubMenu(groupId:String){if(!::annotSubMenuRow.isInitialized)return;annotSubMenuRow.removeAllViews();ANNOT_GROUPS[groupId]?.forEach{toolId->annotSubMenuRow.addView(buildAnnotToolCell(toolId))}}
    private fun buildAnnotToolCell(toolId:String):LinearLayout{
        val isActive=toolId==activeTool;val cOn=Color.parseColor("#001A41");val cOff=Color.parseColor("#ADC6FF")
        return LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;layoutParams=LinearLayout.LayoutParams(dp(62),dp(68)).apply{marginEnd=dp(5)};background=if(isActive)GradientDrawable().apply{colors=intArrayOf(Color.parseColor("#ADC6FF"),Color.parseColor("#4B8EFF"));gradientType=GradientDrawable.LINEAR_GRADIENT;orientation=GradientDrawable.Orientation.TL_BR;cornerRadius=dp(12).toFloat()} else GradientDrawable().apply{setColor(Color.parseColor("#2D2D2D"));cornerRadius=dp(12).toFloat()};elevation=if(isActive)dp(4).toFloat() else dp(2).toFloat()
            addView(ImageView(this@ViewerActivity).apply{layoutParams=LinearLayout.LayoutParams(dp(20),dp(20));setImageResource(TOOL_ICON[toolId]?:android.R.drawable.ic_menu_edit);colorFilter=PorterDuffColorFilter(if(isActive)cOn else cOff,PorterDuff.Mode.SRC_IN);alpha=if(isActive)1f else 0.75f})
            addView(TextView(this@ViewerActivity).apply{text=TOOL_LABEL[toolId]?:toolId;textSize=8.5f;typeface=Typeface.DEFAULT_BOLD;setTextColor(if(isActive)cOn else Color.WHITE);alpha=if(isActive)1f else 0.65f;gravity=Gravity.CENTER;setPadding(0,dp(3),0,0)})
            setOnClickListener{handleAnnotToolTap(toolId)}
        }
    }
    private fun handleAnnotToolTap(toolId:String){
        when(toolId){"save"->{saveAnnotations();return};"image"->{showImageInsertDialog();return};"stamp"->{showStampDialog();return};"text"->{showTextInsertDialog();return}}
        activeTool=if(activeTool==toolId)null else toolId;if(activeTool=="highlight")activeColor=highlightColor
        val ownerGroup=ANNOT_GROUPS.entries.firstOrNull{toolId in it.value}?.key?:activeAnnotGroup;if(ownerGroup!=activeAnnotGroup){activeAnnotGroup=ownerGroup;rebuildAnnotGroupNav()}
        refreshAnnotSubMenu(activeAnnotGroup);if(activeTool!=null)toast("${TOOL_LABEL[toolId]} -- draw on page")
    }
    private fun showImageInsertDialog(){AlertDialog.Builder(this).setTitle("Insert Image").setItems(arrayOf("Pick from Gallery","Use Camera (via Scanner)")){_,w->when(w){0->imagePicker.launch("image/*");1->toast("Use Scanner tab to capture, then import")}}.show()}
    private fun showStampDialog(){val stamps=arrayOf("APPROVED","REJECTED","REVIEWED","DRAFT","CONFIDENTIAL","URGENT","PAID","VOID","Custom...");AlertDialog.Builder(this).setTitle("Select Stamp").setItems(stamps){_,i->if(i<stamps.size-1)placeStampOnCurrentPage(stamps[i]) else{val et=EditText(this).apply{hint="Enter stamp text"};AlertDialog.Builder(this).setTitle("Custom Stamp").setView(et).setPositiveButton("OK"){_,_->val t=et.text.toString().trim();if(t.isNotEmpty())placeStampOnCurrentPage(t)}.show()}}.show()}
    private fun placeStampOnCurrentPage(text:String){if(annotOverlays[currentPage]==null)return;annotationManager.add(Annotation(pageIndex=currentPage,type=AnnotationType.STAMP,points=listOf(PointF(80f,180f)),color=Color.RED,strokeWidth=8f,text=text));annotOverlays[currentPage]?.invalidate();toast("Stamp on page ${currentPage+1}")}
    private fun showTextInsertDialog(){
        val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(8),dp(16),dp(8))}
        fun fmtBtn(label:String,isActive:Boolean,action:()->Unit):TextView=TextView(this).apply{text=label;textSize=13f;typeface=Typeface.DEFAULT_BOLD;gravity=Gravity.CENTER;setPadding(dp(10),dp(6),dp(10),dp(6));layoutParams=LinearLayout.LayoutParams(-2,-2).apply{marginEnd=dp(6)};setTextColor(if(isActive)Color.parseColor("#001A41") else Color.parseColor("#ADC6FF"));background=GradientDrawable().apply{setColor(if(isActive)Color.parseColor("#ADC6FF") else Color.parseColor("#2D2D2D"));cornerRadius=dp(6).toFloat()};setOnClickListener{action()}}
        val fmtRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,dp(4),0,dp(8))}
        fmtRow.addView(fmtBtn("B",textBold){textBold=!textBold;showTextInsertDialog()});fmtRow.addView(fmtBtn("I",textItalic){textItalic=!textItalic;showTextInsertDialog()});fmtRow.addView(fmtBtn("U",textUnderline){textUnderline=!textUnderline;showTextInsertDialog()})
        val alignRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;setPadding(0,0,0,dp(8))}
        listOf("L" to "left","C" to "center","R" to "right").forEach{(lbl,al)->alignRow.addView(fmtBtn(lbl,textAlign==al){textAlign=al;showTextInsertDialog()})}
        alignRow.addView(TextView(this).apply{text="Size:";textSize=11f;setTextColor(Color.parseColor("#8B90A0"));setPadding(dp(8),0,dp(4),0);gravity=Gravity.CENTER_VERTICAL})
        val sizeEt=EditText(this).apply{setText(textFontSize.toInt().toString());inputType=android.text.InputType.TYPE_CLASS_NUMBER;layoutParams=LinearLayout.LayoutParams(dp(50),-2);setTextColor(Color.WHITE);setPadding(dp(6),dp(4),dp(6),dp(4))};alignRow.addView(sizeEt)
        val et=EditText(this).apply{hint="Enter annotation text";setPadding(dp(12),dp(8),dp(12),dp(8));setTextColor(Color.WHITE);setHintTextColor(Color.parseColor("#666888"));textSize=textFontSize;typeface=when{textBold&&textItalic->Typeface.create(Typeface.DEFAULT,Typeface.BOLD_ITALIC);textBold->Typeface.DEFAULT_BOLD;textItalic->Typeface.create(Typeface.DEFAULT,Typeface.ITALIC);else->Typeface.DEFAULT};gravity=when(textAlign){"center"->Gravity.CENTER_HORIZONTAL;"right"->Gravity.END;else->Gravity.START};if(textUnderline)paintFlags=paintFlags or Paint.UNDERLINE_TEXT_FLAG}
        container.addView(fmtRow);container.addView(alignRow);container.addView(et)
        AlertDialog.Builder(this).setTitle("Add Text Note").setView(container).setPositiveButton("Place"){_,_->
            val txt=et.text.toString().trim();val sz=sizeEt.text.toString().toFloatOrNull()?:textFontSize;textFontSize=sz.coerceIn(8f,72f)
            if(txt.isNotEmpty()){val w=resources.displayMetrics.widthPixels/2f;val h=(pageContainer.getChildAt(currentPage)?.height?.toFloat()?:400f)/2f;val fmt="${if(textBold)"B" else ""}${if(textItalic)"I" else ""}${if(textUnderline)"U" else ""}:${textAlign}:${textFontSize.toInt()}"
                annotationManager.add(Annotation(pageIndex=currentPage,type=AnnotationType.TEXT,points=listOf(PointF(w-dp(40),h)),color=activeColor,strokeWidth=textFontSize,text="$fmt|$txt"))
                annotOverlays[currentPage]?.invalidate();toast("Text placed on page ${currentPage+1}")}
        }.setNegativeButton("Cancel",null).show()
    }

    private fun saveAnnotations(){if(!annotationManager.hasAny()){toast("No annotations to save");return};AlertDialog.Builder(this).setTitle("Save document").setItems(arrayOf("Save (overwrite)","Save As (new file)")){_,which->exportAnnotatedPdf(saveAs=which==1)}.show()}
    private fun exportAnnotatedPdf(saveAs:Boolean){
        lifecycleScope.launch{toast("Saving annotations...")
            val result=withContext(Dispatchers.IO){try{
                val workingOut=File(cacheDir,"annotated_${System.currentTimeMillis()}.pdf");val bitmapPdf=android.graphics.pdf.PdfDocument();val renderer2=AnnotationRenderer()
                try{for(i in 0 until pageContainer.childCount){val frame=pageContainer.getChildAt(i) as? FrameLayout?:continue;val zoom=frame.getChildAt(0) as? ZoomableImageView?:continue;val bmp=zoom.getRenderedBitmap()?:continue;val out=bmp.copy(Bitmap.Config.ARGB_8888,true);renderer2.render(Canvas(out),annotationManager.get(i));val pi=android.graphics.pdf.PdfDocument.PageInfo.Builder(out.width,out.height,i+1).create();val page=bitmapPdf.startPage(pi);page.canvas.drawBitmap(out,0f,0f,null);bitmapPdf.finishPage(page);out.recycle()};FileOutputStream(workingOut).use{bitmapPdf.writeTo(it)}}finally{bitmapPdf.close()}
                val uri=pdfUri;if(!saveAs&&uri!=null&&writeToUri(workingOut,uri)){persistAnnotationCache();true} else saveOutputToDownloads(workingOut)
            }catch(_:Exception){false}}
            toast(if(result)"Saved to Downloads/ProPDF" else "Save failed -- check storage permission")
        }
    }
    private fun writeToUri(source:File,targetUri:Uri):Boolean{return try{val out=contentResolver.openOutputStream(targetUri,"wt")?:return false;out.use{o->source.inputStream().use{it.copyTo(o)}};true}catch(_:Exception){false}}
    private fun saveOutputToDownloads(source:File):Boolean{
        return try{val fileName="annotated_${System.currentTimeMillis()}.pdf"
            if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q){val values=ContentValues().apply{put(MediaStore.MediaColumns.DISPLAY_NAME,fileName);put(MediaStore.MediaColumns.MIME_TYPE,"application/pdf");put(MediaStore.MediaColumns.RELATIVE_PATH,"${Environment.DIRECTORY_DOWNLOADS}/ProPDF")};val outUri=contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);if(outUri!=null)contentResolver.openOutputStream(outUri)?.use{out->source.inputStream().use{it.copyTo(out)}}}
            else{val dir=File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"ProPDF").also{it.mkdirs()};FileOutputStream(File(dir,fileName)).use{out->source.inputStream().use{it.copyTo(out)}}}
            persistAnnotationCache();true}catch(_:Exception){false}
    }
    private fun persistAnnotationCache(){try{val uri=pdfUri?:return;File(cacheDir,"annot_${uri.toString().hashCode()}.json").writeText(annotationManager.toJson())}catch(_:Exception){}}
    private fun loadAnnotationsFromCache(){
        lifecycleScope.launch(Dispatchers.IO){try{val uri=pdfUri?:return@launch;val file=File(cacheDir,"annot_${uri.toString().hashCode()}.json");if(file.exists()){val json=file.readText();withContext(Dispatchers.Main){annotationManager.fromJson(json);annotOverlays.values.forEach{it.invalidate()};if(annotationManager.hasAny())toast("Annotations restored")}}}catch(_:Exception){}}
    }
    private fun hideKeyboard(){(getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(currentFocus?.windowToken,0)}
    private fun toast(msg:String)=Toast.makeText(this,msg,Toast.LENGTH_SHORT).show()
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun formatSize(bytes:Long)=when{bytes>1_048_576->"%.1f MB".format(bytes/1_048_576.0);bytes>1024->"%.0f KB".format(bytes/1024.0);else->"$bytes B"}

    inner class ZoomableImageView(context:Context,private val bmp:Bitmap,val pageIdx:Int):FrameLayout(context){
        private val iv=ImageView(context).apply{setImageBitmap(bmp);scaleType=ImageView.ScaleType.MATRIX;adjustViewBounds=false}
        private var scaleFactor=1f;private var transX=0f;private var transY=0f;private var lastTouchX=0f;private var lastTouchY=0f;private var isDragging=false;private var viewW=0;private var viewH=0;private val drawMatrix=Matrix()
        private val scaleGD=ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){override fun onScale(det:ScaleGestureDetector):Boolean{val old=scaleFactor;scaleFactor=(scaleFactor*det.scaleFactor).coerceIn(0.5f,5f);transX=det.focusX-(det.focusX-transX)*(scaleFactor/old);transY=det.focusY-(det.focusY-transY)*(scaleFactor/old);clampTranslation();applyTransform();return true}})
        private val gestureDetector=GestureDetector(context,object:GestureDetector.SimpleOnGestureListener(){override fun onDoubleTap(e:MotionEvent):Boolean{if(scaleFactor>1.2f)resetTransform() else{scaleFactor=2.5f;transX=viewW/2f-bmp.width*scaleFactor/2f;transY=viewH/2f-bmp.height*scaleFactor/2f;clampTranslation();applyTransform()};return true}})
        init{addView(iv)}
        override fun onSizeChanged(w:Int,h:Int,ow:Int,oh:Int){super.onSizeChanged(w,h,ow,oh);viewW=w;viewH=h;fitToWidth()}
        fun fitToWidth(){if(viewW==0||bmp.width==0)return;scaleFactor=viewW.toFloat()/bmp.width.toFloat();transX=0f;transY=0f;applyTransform()}
        fun fitToPage(){if(viewW==0||viewH==0||bmp.width==0||bmp.height==0)return;scaleFactor=minOf(viewW.toFloat()/bmp.width,viewH.toFloat()/bmp.height);transX=(viewW-bmp.width*scaleFactor)/2f;transY=(viewH-bmp.height*scaleFactor)/2f;applyTransform()}
        fun resetTransform(){scaleFactor=1f;transX=0f;transY=0f;fitToWidth()}
        private fun clampTranslation(){val sw=bmp.width*scaleFactor;val sh=bmp.height*scaleFactor;transX=if(sw<=viewW)(viewW-sw)/2f else transX.coerceIn(viewW-sw,0f);transY=if(sh<=viewH)(viewH-sh)/2f else transY.coerceIn(viewH-sh,0f)}
        private fun applyTransform(){drawMatrix.reset();drawMatrix.postScale(scaleFactor,scaleFactor);drawMatrix.postTranslate(transX,transY);iv.imageMatrix=drawMatrix}
        fun applyFilter(filter:android.graphics.ColorFilter?){iv.colorFilter=filter}
        fun getRenderedBitmap():Bitmap?=bmp.takeIf{!it.isRecycled}
        override fun onTouchEvent(ev:MotionEvent):Boolean{
            gestureDetector.onTouchEvent(ev);scaleGD.onTouchEvent(ev);if(scaleGD.isInProgress)return true
            when(ev.actionMasked){MotionEvent.ACTION_DOWN->{lastTouchX=ev.x;lastTouchY=ev.y;isDragging=false};MotionEvent.ACTION_MOVE->{if(scaleFactor>1.05f){val dx=ev.x-lastTouchX;val dy=ev.y-lastTouchY;if(Math.abs(dx)>dp(2)||Math.abs(dy)>dp(2)){transX+=dx;transY+=dy;lastTouchX=ev.x;lastTouchY=ev.y;isDragging=true;clampTranslation();applyTransform()}}};MotionEvent.ACTION_UP->{if(!isDragging&&scaleFactor<=1.05f)return false}}
            return scaleFactor>1.05f||scaleGD.isInProgress
        }
        private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    }

    companion object {
        const val EXTRA_URI          = "extra_pdf_uri"
        const val EXTRA_PASSWORD     = "extra_pdf_password"
        const val EXTRA_DISPLAY_NAME = "extra_pdf_display_name"
        fun start(context:Context,uri:Uri,password:String?=null,displayName:String?=null){
            context.startActivity(Intent(context,ViewerActivity::class.java).apply{
                putExtra(EXTRA_URI,uri.toString());if(password!=null)putExtra(EXTRA_PASSWORD,password);if(displayName!=null)putExtra(EXTRA_DISPLAY_NAME,displayName)
            })
        }
    }
}
