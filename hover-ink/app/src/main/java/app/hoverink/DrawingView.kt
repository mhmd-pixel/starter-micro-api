package app.hoverink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max

/**
 * A canvas that draws while the S Pen hovers above the screen (and also when
 * it touches). Strokes are drawn straight into a bitmap as they arrive and are
 * kept as a list so they can be undone and redone.
 */
class DrawingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** When hovering puts ink down. */
    enum class HoverMode { ALWAYS, TAP_TOGGLE, HOLD_BUTTON }

    enum class Background(val color: Int, val grid: Boolean) {
        PAPER(Color.WHITE, false),
        GRID(Color.WHITE, true),
        DARK(Color.rgb(18, 18, 20), false),
    }

    interface Listener {
        /** [hovering]: pen is in range. [inking]: hover would draw right now. */
        fun onPenState(hovering: Boolean, inking: Boolean)
        fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean)
    }

    private class Pt(val x: Float, val y: Float, val w: Float)

    private class Stroke(val color: Int, val eraser: Boolean, val isClear: Boolean = false) {
        val pts = ArrayList<Pt>()
        var segmentsDrawn = 1 // segment k joins pts[k-1]..pts[k]; segment indices start at 1
    }

    var listener: Listener? = null

    var hoverMode = HoverMode.ALWAYS
        set(v) {
            field = v
            tapInkOn = false
            endStroke()
            notifyPen()
        }
    var color = Color.BLACK
    var eraser = false
    /** Brush diameter in px. */
    var brushWidth = 8f
    /** 0 = raw input, up to 0.95 = very smooth (more lag). */
    var steadiness = 0.35f
    var fingerDraws = false
    var background = Background.PAPER
        set(v) {
            field = v
            invalidate()
        }

    private val strokes = ArrayList<Stroke>()
    private val redoStack = ArrayList<Stroke>()
    private var current: Stroke? = null
    private var base: Bitmap? = null
    private var layer: Bitmap? = null
    private var layerCanvas: Canvas? = null

    // Smoothed pen position.
    private var sx = 0f
    private var sy = 0f

    // Hover cursor.
    private var hovering = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var inkingNow = false

    // Tap-to-toggle state.
    private var tapInkOn = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var holdingForTap = false
    private val touchSlop = 10f * resources.displayMetrics.density

    private val density = resources.displayMetrics.density
    private val segPath = Path()
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val gridPaint = Paint().apply { color = Color.rgb(220, 228, 240); strokeWidth = 1f }
    private val cursorOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
    }
    private val cursorInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * resources.displayMetrics.density
    }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dashed = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)

    init {
        // We draw our own cursor; keep hover events coming to us.
        isFocusable = true
    }

    // --- Public actions --------------------------------------------------------------

    fun undo() {
        endStroke()
        if (strokes.isEmpty()) return
        redoStack += strokes.removeAt(strokes.lastIndex)
        renderAll()
        notifyHistory()
    }

    fun redo() {
        endStroke()
        if (redoStack.isEmpty()) return
        val s = redoStack.removeAt(redoStack.lastIndex)
        strokes += s
        layerCanvas?.let { renderStroke(it, s) }
        invalidate()
        notifyHistory()
    }

    /** Undoable. */
    fun clear() {
        endStroke()
        if (strokes.isEmpty() && base == null) return
        val s = Stroke(0, eraser = false, isClear = true)
        strokes += s
        redoStack.clear()
        layer?.eraseColor(Color.TRANSPARENT)
        invalidate()
        notifyHistory()
    }

    val isEmpty: Boolean
        get() {
            val lastClear = strokes.indexOfLast { it.isClear }
            return if (lastClear >= 0) lastClear == strokes.lastIndex else strokes.isEmpty() && base == null
        }

    /** The drawing with its background, for saving or sharing. */
    fun export(includeBackground: Boolean = true): Bitmap {
        endStroke()
        val out = Bitmap.createBitmap(max(width, 1), max(height, 1), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        if (includeBackground) drawBackground(c)
        layer?.let { c.drawBitmap(it, 0f, 0f, null) }
        return out
    }

    /** Ink only (transparent background), for keeping the drawing between launches. */
    fun snapshotInk(): Bitmap? {
        endStroke()
        return layer?.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Restores a drawing saved earlier as the bottom layer (not undoable past it). */
    fun restoreInk(bitmap: Bitmap) {
        base = bitmap
        strokes.clear()
        redoStack.clear()
        renderAll()
        notifyHistory()
    }

    // --- Input -----------------------------------------------------------------------------

    override fun onHoverEvent(e: MotionEvent): Boolean {
        val tool = e.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return super.onHoverEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER -> {
                hovering = true
                endStroke()
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                hovering = true
                val ink = when (hoverMode) {
                    HoverMode.ALWAYS -> true
                    HoverMode.TAP_TOGGLE -> tapInkOn
                    HoverMode.HOLD_BUTTON -> e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0
                }
                if (ink) {
                    if (current == null) startStroke(e.getX(0), e.getY(0), tool == MotionEvent.TOOL_TYPE_ERASER)
                    for (h in 0 until e.historySize) addPoint(e.getHistoricalX(0, h), e.getHistoricalY(0, h), brushWidth)
                    addPoint(e.getX(0), e.getY(0), brushWidth)
                    drawPending()
                } else {
                    endStroke()
                }
                inkingNow = ink
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                // Also sent just before the pen touches down.
                endStroke()
                hovering = false
                inkingNow = false
            }
        }
        cursorX = e.getX(0)
        cursorY = e.getY(0)
        notifyPen()
        invalidate()
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val tool = e.getToolType(0)
        val isPen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER || tool == MotionEvent.TOOL_TYPE_MOUSE
        if (!isPen && !fingerDraws) return true // palm rejection: fingers do nothing
        val w = { p: Float -> if (isPen) brushWidth * (0.45f + 0.9f * p.coerceIn(0f, 1f)) else brushWidth }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)
                endStroke()
                downTime = e.eventTime
                downX = e.x
                downY = e.y
                // In tap-toggle mode, hold the ink back until we know it isn't a tap.
                holdingForTap = isPen && hoverMode == HoverMode.TAP_TOGGLE
                startStroke(e.x, e.y, tool == MotionEvent.TOOL_TYPE_ERASER)
                addPoint(e.x, e.y, w(e.pressure))
                if (!holdingForTap) drawPending()
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) addPoint(e.getHistoricalX(0, h), e.getHistoricalY(0, h), w(e.getHistoricalPressure(0, h)))
                addPoint(e.x, e.y, w(e.pressure))
                if (holdingForTap && (hypot(e.x - downX, e.y - downY) > touchSlop || e.eventTime - downTime > TAP_MS)) {
                    holdingForTap = false
                }
                if (!holdingForTap) drawPending()
            }
            MotionEvent.ACTION_UP -> {
                if (holdingForTap) {
                    // A quick pen tap: toggle hover ink instead of drawing a dot.
                    current = null
                    holdingForTap = false
                    tapInkOn = !tapInkOn
                    performClick()
                } else {
                    addPoint(e.x, e.y, w(e.pressure))
                    drawPending()
                    endStroke()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (holdingForTap) current = null else endStroke()
                holdingForTap = false
            }
        }
        cursorX = e.x
        cursorY = e.y
        notifyPen()
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun startStroke(x: Float, y: Float, penEraser: Boolean) {
        current = Stroke(color, eraser || penEraser)
        sx = x
        sy = y
    }

    private fun addPoint(x: Float, y: Float, w: Float) {
        val s = current ?: return
        if (s.pts.isEmpty()) {
            sx = x
            sy = y
        } else {
            val k = 1f - steadiness.coerceIn(0f, 0.95f)
            sx += (x - sx) * k
            sy += (y - sy) * k
            val last = s.pts.last()
            if (hypot(sx - last.x, sy - last.y) < 0.75f) return
        }
        s.pts += Pt(sx, sy, w)
    }

    /** Draws any segments of the current stroke not drawn yet. */
    private fun drawPending() {
        val s = current ?: return
        val c = layerCanvas ?: return
        drawSegments(c, s, s.segmentsDrawn, s.pts.size)
        s.segmentsDrawn = max(s.segmentsDrawn, s.pts.size)
    }

    private fun endStroke() {
        val s = current ?: return
        current = null
        if (s.pts.isEmpty()) return
        layerCanvas?.let {
            drawSegments(it, s, s.segmentsDrawn, s.pts.size)
            drawTail(it, s)
        }
        strokes += s
        redoStack.clear()
        notifyHistory()
        invalidate()
    }

    // --- Rendering --------------------------------------------------------------------------

    private fun paintFor(s: Stroke, w: Float): Paint = inkPaint.apply {
        color = s.color
        strokeWidth = w
        xfermode = if (s.eraser) clearMode else null
    }

    private fun mid(a: Float, b: Float) = (a + b) / 2f

    /** Smooth quadratic segments through the midpoints of consecutive points. */
    private fun drawSegments(c: Canvas, s: Stroke, from: Int, to: Int) {
        val p = s.pts
        for (k in max(from, 1) until to) {
            val a = p[k - 1]
            val b = p[k]
            segPath.rewind()
            if (k == 1) segPath.moveTo(a.x, a.y) else segPath.moveTo(mid(p[k - 2].x, a.x), mid(p[k - 2].y, a.y))
            segPath.quadTo(a.x, a.y, mid(a.x, b.x), mid(a.y, b.y))
            c.drawPath(segPath, paintFor(s, (a.w + b.w) / 2f))
        }
    }

    private fun drawTail(c: Canvas, s: Stroke) {
        val p = s.pts
        if (p.size == 1) {
            dotPaint.color = s.color
            dotPaint.xfermode = if (s.eraser) clearMode else null
            c.drawCircle(p[0].x, p[0].y, p[0].w / 2f, dotPaint)
            return
        }
        val a = p[p.size - 2]
        val b = p.last()
        c.drawLine(mid(a.x, b.x), mid(a.y, b.y), b.x, b.y, paintFor(s, b.w))
    }

    private fun renderStroke(c: Canvas, s: Stroke) {
        if (s.isClear) {
            layer?.eraseColor(Color.TRANSPARENT)
            return
        }
        if (s.pts.isEmpty()) return
        drawSegments(c, s, 1, s.pts.size)
        drawTail(c, s)
    }

    private fun renderAll() {
        val l = layer ?: return
        val c = layerCanvas ?: return
        l.eraseColor(Color.TRANSPARENT)
        base?.let { c.drawBitmap(it, 0f, 0f, null) }
        strokes.forEach { renderStroke(c, it) }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        // Keep the larger size so rotating back and forth never crops the drawing.
        val old = layer
        val nw = max(w, old?.width ?: 0)
        val nh = max(h, old?.height ?: 0)
        if (old != null && old.width == nw && old.height == nh) return
        layer = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888).also { layerCanvas = Canvas(it) }
        renderAll()
    }

    private fun drawBackground(c: Canvas) {
        c.drawColor(background.color)
        if (background.grid) {
            val step = 24f * density
            var x = step
            while (x < c.width) { c.drawLine(x, 0f, x, c.height.toFloat(), gridPaint); x += step }
            var y = step
            while (y < c.height) { c.drawLine(0f, y, c.width.toFloat(), y, gridPaint); y += step }
        }
    }

    override fun onDraw(canvas: Canvas) {
        drawBackground(canvas)
        layer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (hovering) drawCursor(canvas)
    }

    private fun drawCursor(c: Canvas) {
        val r = max(brushWidth / 2f, 3f * density)
        val dark = background == Background.DARK
        val erasing = eraser
        if (inkingNow && !erasing) {
            cursorFill.color = color
            c.drawCircle(cursorX, cursorY, r, cursorFill)
        }
        cursorOuter.color = if (dark) Color.WHITE else Color.argb(200, 0, 0, 0)
        cursorInner.color = if (dark) Color.argb(160, 0, 0, 0) else Color.argb(200, 255, 255, 255)
        cursorOuter.pathEffect = if (erasing) dashed else null
        c.drawCircle(cursorX, cursorY, r + cursorOuter.strokeWidth / 2f, cursorOuter)
        c.drawCircle(cursorX, cursorY, r + cursorOuter.strokeWidth + cursorInner.strokeWidth / 2f, cursorInner)
        if (!inkingNow) {
            // Small crosshair so the exact point is visible while not inking.
            val t = 3f * density
            c.drawLine(cursorX - t, cursorY, cursorX + t, cursorY, cursorOuter)
            c.drawLine(cursorX, cursorY - t, cursorX, cursorY + t, cursorOuter)
        }
    }

    private fun notifyPen() {
        val wouldInk = when (hoverMode) {
            HoverMode.ALWAYS -> true
            HoverMode.TAP_TOGGLE -> tapInkOn
            HoverMode.HOLD_BUTTON -> inkingNow
        }
        listener?.onPenState(hovering, wouldInk)
    }

    private fun notifyHistory() = listener?.onHistoryChanged(strokes.isNotEmpty(), redoStack.isNotEmpty())

    /** Whether tap-toggle hover ink is currently on. */
    val isTapInkOn get() = tapInkOn

    companion object {
        private const val TAP_MS = 250L
    }
}
