package app.hoverink

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * A canvas that draws while the S Pen hovers above the screen (and also when
 * it touches). Strokes are drawn into a bitmap as they arrive and are kept as
 * a list so they can be undone and redone.
 */
class DrawingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** When hovering puts ink down. */
    enum class HoverMode { ALWAYS, TAP_TOGGLE, HOLD_BUTTON }

    enum class Tool {
        /** Ballpoint: solid line, pressure changes width. */
        PEN,
        /** Grainy graphite made of many small dabs; passes build up. */
        PENCIL,
        /** Calligraphy nib: thick or thin depending on direction. */
        FOUNTAIN,
        /** See-through highlighter that multiplies with what's underneath. */
        MARKER,
        /** Ink brush: thicker when slow, thinner when fast. */
        BRUSH,
    }

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

    private class Pt(val x: Float, val y: Float, val w: Float, val a: Float)

    /** Random-number and spacing state for dab-based tools; replayed identically on redraw. */
    private class StampState(seed: Long) {
        var rng = seed or 1L
        var carry = 0f
        fun next(): Float {
            rng = rng xor (rng shl 13)
            rng = rng xor (rng ushr 7)
            rng = rng xor (rng shl 17)
            return ((rng ushr 40).toFloat() / (1 shl 24).toFloat())
        }
    }

    private class Stroke(
        val tool: Tool,
        val color: Int,
        val width: Float,
        val opacity: Int,
        val eraser: Boolean,
        val isClear: Boolean = false,
        val seed: Long = 0L,
    ) {
        val pts = ArrayList<Pt>()
        /** Pieces already drawn: 0 = nothing yet; segment k (k ≥ 1) joins pts[k-1]..pts[k]. */
        var segmentsDrawn = 0
        var stamp = StampState(seed)
        val bounds = RectF()
        var lastTime = 0L
        var lastW = 0f
    }

    var listener: Listener? = null
    /** False for the preview inside the pen dialog. */
    var inputEnabled = true

    var hoverMode = HoverMode.ALWAYS
        set(v) {
            field = v
            tapInkOn = false
            endStroke()
            notifyPen()
        }
    var tool = Tool.PEN
    var color = Color.BLACK
    var eraser = false
    /** Diameter in px of the current pen. */
    var brushWidth = 8f
    /** Diameter in px of the eraser. */
    var eraserWidth = 24f
    /** 0–100. */
    var opacity = 100
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
    /** Holds an in-progress see-through stroke so it can be blended in one go. */
    private var live: Bitmap? = null
    private var liveCanvas: Canvas? = null

    private var sx = 0f
    private var sy = 0f

    private var hovering = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var inkingNow = false

    private var tapInkOn = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var holdingForTap = false
    private val density = resources.displayMetrics.density
    private val touchSlop = 10f * density
    private var seedCounter = System.nanoTime()

    private val segPath = Path()
    private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dabPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val compositePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val clearMode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    private val gridPaint = Paint().apply { color = Color.rgb(220, 228, 240); strokeWidth = 1f }
    private val cursorOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * density }
    private val cursorInner = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density }
    private val cursorFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dashed = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
    private val tmpRect = Rect()

    init {
        isFocusable = true
    }

    // --- Public actions ------------------------------------------------------------------

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
        strokes += Stroke(Tool.PEN, 0, 0f, 100, eraser = false, isClear = true)
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

    /** The drawing, with its background unless [includeBackground] is false. */
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

    private var demo: (() -> Unit)? = null

    /** Draws a sample scribble with the current settings (used by the pen dialog preview). */
    fun showDemo() {
        demo = {
            strokes.clear()
            redoStack.clear()
            current = null
            val w = width.toFloat()
            val h = height.toFloat()
            if (w > 0 && h > 0) {
                var t = 0L
                startStroke(w * 0.08f, h / 2f, false)
                // Left to right, then back over the middle: shows how passes build up.
                val n = 70
                for (i in 0..n) {
                    val f = i / n.toFloat()
                    addPoint(w * (0.08f + 0.84f * f), h / 2f + h * 0.28f * sin(f * 2 * Math.PI).toFloat(), -1f, t)
                    t += 8 + (i % 20).toLong()
                }
                for (i in 0..n / 2) {
                    val f = 1f - i / (n / 2f) * 0.6f
                    addPoint(w * (0.08f + 0.84f * f), h / 2f + h * 0.28f * sin(f * 2 * Math.PI).toFloat() + 3 * density, -1f, t)
                    t += 10
                }
                drawPending()
                endStroke()
            }
            renderAll()
        }
        demo?.invoke()
    }

    // --- Input ------------------------------------------------------------------------------

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (!inputEnabled) return false
        val toolType = e.getToolType(0)
        if (toolType != MotionEvent.TOOL_TYPE_STYLUS && toolType != MotionEvent.TOOL_TYPE_ERASER) return super.onHoverEvent(e)
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
                    if (current == null) startStroke(e.getX(0), e.getY(0), toolType == MotionEvent.TOOL_TYPE_ERASER)
                    for (h in 0 until e.historySize) addPoint(e.getHistoricalX(0, h), e.getHistoricalY(0, h), -1f, e.getHistoricalEventTime(h))
                    addPoint(e.getX(0), e.getY(0), -1f, e.eventTime)
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
        if (!inputEnabled) return false
        val toolType = e.getToolType(0)
        val isPen = toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER || toolType == MotionEvent.TOOL_TYPE_MOUSE
        if (!isPen && !fingerDraws) return true // palm rejection: fingers do nothing
        val p = { v: Float -> if (isPen) v.coerceIn(0f, 1f) else -1f }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestUnbufferedDispatch(e)
                endStroke()
                downTime = e.eventTime
                downX = e.x
                downY = e.y
                // In tap-toggle mode, hold the ink back until we know it isn't a tap.
                holdingForTap = isPen && hoverMode == HoverMode.TAP_TOGGLE
                startStroke(e.x, e.y, toolType == MotionEvent.TOOL_TYPE_ERASER)
                addPoint(e.x, e.y, p(e.pressure), e.eventTime)
                if (!holdingForTap) drawPending()
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    addPoint(e.getHistoricalX(0, h), e.getHistoricalY(0, h), p(e.getHistoricalPressure(0, h)), e.getHistoricalEventTime(h))
                }
                addPoint(e.x, e.y, p(e.pressure), e.eventTime)
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
                    addPoint(e.x, e.y, p(e.pressure), e.eventTime)
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
        val erasing = eraser || penEraser
        current = Stroke(
            tool = tool,
            color = color,
            width = if (erasing) eraserWidth else brushWidth,
            opacity = if (erasing) 100 else opacity.coerceIn(0, 100),
            eraser = erasing,
            seed = ++seedCounter * 0x9E3779B97F4A7C15uL.toLong(),
        )
        sx = x
        sy = y
    }

    /** [p]: stylus pressure 0–1, or -1 when hovering (no pressure). [t]: event time in ms. */
    private fun addPoint(x: Float, y: Float, p: Float, t: Long) {
        val s = current ?: return
        val first = s.pts.isEmpty()
        if (first) {
            sx = x
            sy = y
        } else {
            val k = 1f - steadiness.coerceIn(0f, 0.95f)
            sx += (x - sx) * k
            sy += (y - sy) * k
            val last = s.pts.last()
            if (hypot(sx - last.x, sy - last.y) < 0.75f) return
        }
        val last = s.pts.lastOrNull()
        val pressureWidth = if (p < 0) 1f else 0.45f + 0.9f * p
        var a = 1f
        val w: Float = when {
            s.eraser -> s.width
            s.tool == Tool.PEN -> s.width * pressureWidth
            s.tool == Tool.MARKER -> s.width
            s.tool == Tool.PENCIL -> {
                if (p >= 0) a = 0.35f + 0.65f * p
                s.width * (if (p < 0) 1f else 0.8f + 0.4f * p)
            }
            s.tool == Tool.FOUNTAIN -> {
                // Nib held at 45°: strokes along the nib are thin, across it thick.
                val target = if (last == null) {
                    s.width * 0.6f
                } else {
                    val theta = atan2(sy - last.y, sx - last.x)
                    s.width * (0.18f + 0.95f * abs(sin(theta - NIB_ANGLE)))
                } * pressureWidth
                (if (last == null) target else s.lastW + (target - s.lastW) * 0.35f).also { s.lastW = it }
            }
            else -> { // BRUSH
                val target = if (last == null) {
                    s.width
                } else {
                    val speed = hypot(sx - last.x, sy - last.y) / max(1L, t - s.lastTime) / density // dp per ms
                    s.width * (1.45f - 0.55f * speed).coerceIn(0.3f, 1.45f)
                } * pressureWidth
                val smoothed = if (last == null) target else s.lastW + (target - s.lastW) * 0.25f
                s.lastW = smoothed
                // Ease in at the start like a brush touching down.
                smoothed * min(1f, 0.35f + 0.65f * s.pts.size / 8f)
            }
        }
        s.lastTime = t
        s.pts += Pt(sx, sy, w, a)
    }

    /** Draws any segments of the current stroke not drawn yet. */
    private fun drawPending() {
        val s = current ?: return
        val c = targetFor(s) ?: return
        drawSegments(c, s, s.segmentsDrawn, s.pts.size, s.stamp)
        s.segmentsDrawn = max(s.segmentsDrawn, s.pts.size)
    }

    private fun endStroke() {
        val s = current ?: return
        current = null
        if (s.pts.isEmpty()) return
        targetFor(s)?.let {
            drawSegments(it, s, s.segmentsDrawn, s.pts.size, s.stamp)
            drawTail(it, s, s.stamp)
        }
        if (usesLiveLayer(s)) commitLive(s)
        strokes += s
        redoStack.clear()
        notifyHistory()
        invalidate()
    }

    // --- Rendering ------------------------------------------------------------------------------

    /** Solid see-through strokes are drawn opaque on a side layer, then blended in at their opacity. */
    private fun usesLiveLayer(s: Stroke) = !s.eraser && s.tool != Tool.PENCIL && (s.opacity < 100 || s.tool == Tool.MARKER)

    private fun targetFor(s: Stroke): Canvas? {
        if (!usesLiveLayer(s)) return layerCanvas
        val l = layer ?: return null
        if (live == null || live!!.width != l.width || live!!.height != l.height) {
            live = Bitmap.createBitmap(l.width, l.height, Bitmap.Config.ARGB_8888).also { liveCanvas = Canvas(it) }
        }
        return liveCanvas
    }

    private fun compositeFor(s: Stroke): Paint = compositePaint.apply {
        alpha = s.opacity * 255 / 100
        blendMode = if (s.tool == Tool.MARKER) BlendMode.MULTIPLY else BlendMode.SRC_OVER
    }

    private fun commitLive(s: Stroke) {
        val l = live ?: return
        s.bounds.roundOut(tmpRect)
        if (!tmpRect.intersect(0, 0, l.width, l.height)) return
        layerCanvas?.drawBitmap(l, tmpRect, tmpRect, compositeFor(s))
        liveCanvas?.drawRect(tmpRect, clearPaint)
    }

    private fun solidPaint(s: Stroke, w: Float): Paint = inkPaint.apply {
        color = s.color
        alpha = 255
        strokeWidth = w
        xfermode = if (s.eraser) clearMode else null
    }

    private fun mid(a: Float, b: Float) = (a + b) / 2f

    private fun grow(s: Stroke, x: Float, y: Float, w: Float) {
        val r = w / 2f + 2f
        if (s.bounds.isEmpty) s.bounds.set(x - r, y - r, x + r, y + r) else s.bounds.union(x - r, y - r, x + r, y + r)
    }

    /** Smooth quadratic segments through the midpoints of consecutive points. */
    private fun drawSegments(c: Canvas, s: Stroke, from: Int, to: Int, st: StampState) {
        val p = s.pts
        if (from == 0 && to >= 1 && s.tool == Tool.PENCIL && !s.eraser) {
            dab(c, s, st, p[0].x, p[0].y, p[0].w, p[0].a) // first dab where the pencil lands
        }
        for (k in max(from, 1) until to) {
            val a = p[k - 1]
            val b = p[k]
            val x0 = if (k == 1) a.x else mid(p[k - 2].x, a.x)
            val y0 = if (k == 1) a.y else mid(p[k - 2].y, a.y)
            val w0 = if (k == 1) a.w else mid(p[k - 2].w, a.w)
            val x2 = mid(a.x, b.x)
            val y2 = mid(a.y, b.y)
            val w2 = mid(a.w, b.w)
            grow(s, x0, y0, max(w0, w2) * 1.3f)
            grow(s, a.x, a.y, a.w * 1.3f)
            grow(s, x2, y2, max(w0, w2) * 1.3f)
            if (s.tool == Tool.PENCIL && !s.eraser) {
                stampQuad(c, s, st, x0, y0, w0, a.x, a.y, x2, y2, w2, mid(a.a, b.a))
            } else {
                segPath.rewind()
                segPath.moveTo(x0, y0)
                segPath.quadTo(a.x, a.y, x2, y2)
                c.drawPath(segPath, solidPaint(s, (a.w + b.w) / 2f))
            }
        }
    }

    private fun drawTail(c: Canvas, s: Stroke, st: StampState) {
        val p = s.pts
        val b = p.last()
        if (p.size == 1) {
            grow(s, b.x, b.y, b.w * 1.3f)
            if (s.tool == Tool.PENCIL && !s.eraser) return // the landing dab is already down
            dotPaint.color = s.color
            dotPaint.alpha = 255
            dotPaint.xfermode = if (s.eraser) clearMode else null
            c.drawCircle(b.x, b.y, b.w / 2f, dotPaint)
            return
        }
        val a = p[p.size - 2]
        val mx = mid(a.x, b.x)
        val my = mid(a.y, b.y)
        grow(s, b.x, b.y, b.w * 1.3f)
        if (s.tool == Tool.PENCIL && !s.eraser) {
            stampLine(c, s, st, mx, my, mid(a.w, b.w), b.x, b.y, b.w, b.a)
        } else {
            c.drawLine(mx, my, b.x, b.y, solidPaint(s, b.w))
        }
    }

    // --- Pencil -----------------------------------------------------------------------------------

    private fun stampQuad(c: Canvas, s: Stroke, st: StampState, x0: Float, y0: Float, w0: Float, cx: Float, cy: Float, x2: Float, y2: Float, w2: Float, a: Float) {
        val approx = hypot(cx - x0, cy - y0) + hypot(x2 - cx, y2 - cy)
        val n = max(1, ceil(approx / 2f).toInt())
        var px = x0
        var py = y0
        var pw = w0
        for (i in 1..n) {
            val t = i / n.toFloat()
            val u = 1 - t
            val qx = u * u * x0 + 2 * u * t * cx + t * t * x2
            val qy = u * u * y0 + 2 * u * t * cy + t * t * y2
            val qw = w0 + (w2 - w0) * t
            stampLine(c, s, st, px, py, pw, qx, qy, qw, a)
            px = qx
            py = qy
            pw = qw
        }
    }

    /** Places dabs every few pixels along a line, carrying leftover distance to the next piece. */
    private fun stampLine(c: Canvas, s: Stroke, st: StampState, x0: Float, y0: Float, w0: Float, x1: Float, y1: Float, w1: Float, a: Float) {
        val len = hypot(x1 - x0, y1 - y0)
        if (len <= 0f) return
        var pos = st.carry
        while (pos <= len) {
            val t = pos / len
            val w = w0 + (w1 - w0) * t
            dab(c, s, st, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, w, a)
            pos += spacing(w)
        }
        st.carry = pos - len
    }

    private fun spacing(w: Float) = max(0.8f, w * DAB_SPACING)

    /**
     * One grain-textured dab. The grain is fixed to the paper, so passing over
     * the same spot again darkens it gradually while keeping the texture.
     */
    private fun dab(c: Canvas, s: Stroke, st: StampState, x: Float, y: Float, w: Float, a: Float) {
        val jitterX = (st.next() - 0.5f) * w * 0.22f
        val jitterY = (st.next() - 0.5f) * w * 0.22f
        val sizeVar = 0.8f + st.next() * 0.3f
        val alphaVar = 0.6f + st.next() * 0.8f
        if (st.next() < Grain.SKIP) return // the occasional skip
        // Per-dab alpha calibrated so one pass reaches about the chosen opacity.
        val perDab = Grain.perDabAlpha(s.opacity, 1f / DAB_SPACING)
        if (perDab <= 0f) return
        dabPaint.shader = Grain.shaderFor(resources, s.color)
        dabPaint.alpha = (255 * (perDab * alphaVar * a).coerceIn(0f, 1f)).toInt()
        c.drawCircle(x + jitterX, y + jitterY, max(0.6f, w / 2f * sizeVar), dabPaint)
    }

    private fun renderStroke(c: Canvas, s: Stroke) {
        if (s.isClear) {
            layer?.eraseColor(Color.TRANSPARENT)
            return
        }
        if (s.pts.isEmpty()) return
        val st = StampState(s.seed)
        if (usesLiveLayer(s)) {
            if (s.bounds.isEmpty) computeBounds(s)
            c.saveLayer(s.bounds, compositeFor(s))
            drawSegments(c, s, 0, s.pts.size, st)
            drawTail(c, s, st)
            c.restore()
        } else {
            drawSegments(c, s, 0, s.pts.size, st)
            drawTail(c, s, st)
        }
    }

    private fun computeBounds(s: Stroke) {
        s.pts.forEach { grow(s, it.x, it.y, it.w * 1.3f) }
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
        live = null
        liveCanvas = null
        renderAll()
        demo?.invoke()
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
        val s = current
        if (s != null && usesLiveLayer(s)) live?.let { canvas.drawBitmap(it, 0f, 0f, compositeFor(s)) }
        if (hovering) drawCursor(canvas)
    }

    private fun drawCursor(c: Canvas) {
        val w = if (eraser) eraserWidth else brushWidth
        val r = max(w / 2f, 3f * density)
        val dark = background == Background.DARK
        if (inkingNow && !eraser) {
            cursorFill.color = color
            cursorFill.alpha = (255 * max(0.25f, opacity / 100f)).toInt()
            c.drawCircle(cursorX, cursorY, r, cursorFill)
        }
        cursorOuter.color = if (dark) Color.WHITE else Color.argb(200, 0, 0, 0)
        cursorInner.color = if (dark) Color.argb(160, 0, 0, 0) else Color.argb(200, 255, 255, 255)
        cursorOuter.pathEffect = if (eraser) dashed else null
        c.drawCircle(cursorX, cursorY, r + cursorOuter.strokeWidth / 2f, cursorOuter)
        c.drawCircle(cursorX, cursorY, r + cursorOuter.strokeWidth + cursorInner.strokeWidth / 2f, cursorInner)
        if (!inkingNow) {
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
        private const val NIB_ANGLE = (-Math.PI / 4).toFloat()
        /** Dab spacing as a fraction of the pencil width (≈6 dabs overlap any point). */
        private const val DAB_SPACING = 0.16f
    }
}

/** Tileable paper-grain texture for the pencil, tinted per colour. */
private object Grain {
    private const val SIZE = 256
    private var alpha: IntArray? = null
    private val cache = object : LinkedHashMap<Int, BitmapShader>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, BitmapShader>?) = size > 12
    }

    fun shaderFor(res: android.content.res.Resources, color: Int): BitmapShader {
        cache[color]?.let { return it }
        val a = alpha ?: generate().also { alpha = it }
        val rgb = color and 0xFFFFFF
        val px = IntArray(SIZE * SIZE) { (a[it] shl 24) or rgb }
        val bmp = Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        // Scale the grain with screen density so it reads as paper tooth, not pixel noise.
        val scale = (res.displayMetrics.density / 2.2f).coerceAtLeast(1f)
        shader.setLocalMatrix(android.graphics.Matrix().apply { setScale(scale, scale) })
        cache[color] = shader
        return shader
    }

    private var calibrated: FloatArray? = null

    /**
     * Alpha for each dab so that one pass of overlapping dabs, seen through
     * the grain, covers about [opacity] %. At 100 % the grain still shows a
     * little, like real graphite.
     */
    fun perDabAlpha(opacity: Int, overlap: Float): Float {
        val table = calibrated ?: run {
            val g = (alpha ?: generate().also { alpha = it }).let { a -> FloatArray(a.size / 16) { a[it * 16] / 255f } }
            fun coverage(perDab: Float): Float {
                var sum = 0f
                for (v in g) sum += 1f - (1f - perDab * v).pow(overlap)
                return sum / g.size * (1f - SKIP)
            }
            val maxCov = coverage(1f)
            FloatArray(101) { pct ->
                val target = min(pct / 100f, maxCov)
                var lo = 0f
                var hi = 1f
                repeat(24) {
                    val m = (lo + hi) / 2
                    if (coverage(m) < target) lo = m else hi = m
                }
                if (pct == 0) 0f else hi
            }.also { calibrated = it }
        }
        return table[opacity.coerceIn(0, 100)]
    }

    /** Share of dabs skipped at random. */
    const val SKIP = 0.05f

    /** Fine noise plus coarser blotches, wrapped so the tile repeats seamlessly. */
    private fun generate(): IntArray {
        val rnd = java.util.Random(7)
        val fine = FloatArray(SIZE * SIZE) { rnd.nextFloat() }
        val fineBlur = blur(fine, 1)
        val coarseRaw = FloatArray(SIZE * SIZE) { rnd.nextFloat() }
        val coarse = blur(blur(coarseRaw, 4), 4)
        // Normalise each layer to 0..1.
        fun norm(v: FloatArray): FloatArray {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            v.forEach { lo = min(lo, it); hi = max(hi, it) }
            return FloatArray(v.size) { (v[it] - lo) / (hi - lo + 1e-6f) }
        }
        val f = norm(fineBlur)
        val c = norm(coarse)
        return IntArray(SIZE * SIZE) {
            val n = 0.7f * f[it] + 0.3f * c[it]
            // Contrast curve: plenty of tooth that only fills in with more passes.
            val shaped = ((n - 0.25f) / 0.6f).coerceIn(0f, 1f)
            val smooth = shaped * shaped * (3 - 2 * shaped)
            (255 * (0.12f + 0.88f * smooth)).toInt()
        }
    }

    private fun blur(src: FloatArray, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        val d = (2 * r + 1).toFloat()
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            var sum = 0f
            for (k in -r..r) sum += src[y * SIZE + Math.floorMod(x + k, SIZE)]
            tmp[y * SIZE + x] = sum / d
        }
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            var sum = 0f
            for (k in -r..r) sum += tmp[Math.floorMod(y + k, SIZE) * SIZE + x]
            out[y * SIZE + x] = sum / d
        }
        return out
    }
}
