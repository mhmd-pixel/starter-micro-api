package app.hoverink

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity(), DrawingView.Listener {

    private lateinit var canvas: DrawingView
    private lateinit var pill: TextView
    private lateinit var modeChip: TextView
    private lateinit var toolBtn: ImageButton
    private lateinit var eraserBtn: ImageButton
    private lateinit var colorDot: View
    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val dp get() = resources.displayMetrics.density
    private val autosave by lazy { File(filesDir, "autosave.png") }

    private val hidePill = Runnable { pill.animate().alpha(0f).setDuration(250).start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goFullScreen()

        canvas = findViewById(R.id.canvas)
        pill = findViewById(R.id.pill)
        modeChip = findViewById(R.id.mode)
        toolBtn = findViewById(R.id.tool)
        eraserBtn = findViewById(R.id.eraser)
        colorDot = findViewById(R.id.colorDot)
        undoBtn = findViewById(R.id.undo)
        redoBtn = findViewById(R.id.redo)
        canvas.listener = this
        applyInsets()

        loadSettings()
        modeChip.setOnClickListener { chooseMode() }
        // Tap a tool to select it; tap it again for its settings.
        toolBtn.setOnClickListener { if (canvas.eraser) setEraser(false) else chooseTool() }
        eraserBtn.setOnClickListener { if (canvas.eraser) chooseEraserSize() else setEraser(true) }
        findViewById<View>(R.id.colorButton).setOnClickListener { chooseColor() }
        undoBtn.setOnClickListener { canvas.undo() }
        redoBtn.setOnClickListener { canvas.redo() }
        findViewById<View>(R.id.more).setOnClickListener { showMore(it) }
        onHistoryChanged(canUndo = false, canRedo = false)

        restoreAutosave()
        if (!prefs.getBoolean(KEY_SEEN_HELP, false)) {
            prefs.edit().putBoolean(KEY_SEEN_HELP, true).apply()
            showHelp()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goFullScreen()
    }

    override fun onStop() {
        super.onStop()
        val ink = canvas.snapshotInk() ?: return
        io.execute {
            runCatching {
                val tmp = File(filesDir, "autosave.tmp")
                tmp.outputStream().use { ink.compress(Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(autosave)
            }
        }
    }

    // --- Setup -------------------------------------------------------------------------

    private fun goFullScreen() {
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun applyInsets() {
        val bar = findViewById<View>(R.id.barScroll)
        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (bar.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = (16 * dp).roundToInt() + i.bottom
            (pill.layoutParams as ViewGroup.MarginLayoutParams).topMargin = (16 * dp).roundToInt() + i.top
            bar.requestLayout()
            pill.requestLayout()
            insets
        }
    }

    private fun restoreAutosave() {
        if (!autosave.exists()) return
        io.execute {
            val bmp = runCatching { BitmapFactory.decodeFile(autosave.path) }.getOrNull() ?: return@execute
            main.post { canvas.post { canvas.restoreInk(bmp) } }
        }
    }

    private fun loadSettings() {
        canvas.hoverMode = DrawingView.HoverMode.entries.getOrElse(prefs.getInt(KEY_MODE, 0)) { DrawingView.HoverMode.ALWAYS }
        canvas.background = DrawingView.Background.entries.getOrElse(prefs.getInt(KEY_BG, 0)) { DrawingView.Background.PAPER }
        canvas.color = prefs.getInt(KEY_COLOR, Color.BLACK)
        canvas.tool = DrawingView.Tool.entries.getOrElse(prefs.getInt(KEY_TOOL, 0)) { DrawingView.Tool.PEN }
        canvas.eraserWidth = prefs.getFloat(KEY_ERASER_DP, 12f) * dp
        // Older versions had one brush size for everything; keep it for the pen.
        if (prefs.contains(KEY_WIDTH_DP) && !prefs.contains(widthKey(DrawingView.Tool.PEN))) {
            save { putFloat(widthKey(DrawingView.Tool.PEN), prefs.getFloat(KEY_WIDTH_DP, 3f)) }
        }
        applyToolSettings()
        canvas.steadiness = prefs.getFloat(KEY_STEADY, 0.35f)
        canvas.fingerDraws = prefs.getBoolean(KEY_FINGER, false)
        refreshToolbar()
    }

    private fun save(block: android.content.SharedPreferences.Editor.() -> Unit) = prefs.edit().apply(block).apply()

    private fun refreshToolbar() {
        modeChip.setText(
            when (canvas.hoverMode) {
                DrawingView.HoverMode.ALWAYS -> R.string.mode_always
                DrawingView.HoverMode.TAP_TOGGLE -> R.string.mode_tap
                DrawingView.HoverMode.HOLD_BUTTON -> R.string.mode_button
            },
        )
        toolBtn.setImageResource(TOOL_ICONS.getValue(canvas.tool))
        toolBtn.background = if (!canvas.eraser) getDrawable(R.drawable.selected_bg) else null
        eraserBtn.background = if (canvas.eraser) getDrawable(R.drawable.selected_bg) else null
        colorDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(canvas.color)
            setStroke((1.5f * dp).roundToInt(), Color.argb(80, 128, 128, 128))
        }
    }

    private fun setEraser(on: Boolean) {
        canvas.eraser = on
        refreshToolbar()
    }

    // --- DrawingView.Listener ---------------------------------------------------------

    private var lastPillText: String? = null

    override fun onPenState(hovering: Boolean, inking: Boolean) {
        if (!hovering) {
            main.removeCallbacks(hidePill)
            main.postDelayed(hidePill, 900)
            return
        }
        val text = getString(
            when {
                inking -> R.string.pill_inking
                canvas.hoverMode == DrawingView.HoverMode.TAP_TOGGLE -> R.string.pill_paused_tap
                else -> R.string.pill_paused_button
            },
        )
        if (text != lastPillText) {
            lastPillText = text
            pill.text = text
        }
        main.removeCallbacks(hidePill)
        if (pill.alpha < 1f) pill.animate().alpha(1f).setDuration(120).start()
        // In the always-on mode the pill is just a hint; don't keep it over the drawing.
        if (canvas.hoverMode == DrawingView.HoverMode.ALWAYS) main.postDelayed(hidePill, 1200)
    }

    override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) {
        undoBtn.isEnabled = canUndo
        undoBtn.alpha = if (canUndo) 1f else 0.35f
        redoBtn.isEnabled = canRedo
        redoBtn.alpha = if (canRedo) 1f else 0.35f
    }

    // --- Dialogs --------------------------------------------------------------------------

    private fun chooseMode() {
        val modes = DrawingView.HoverMode.entries
        val labels = arrayOf(getString(R.string.mode_always_long), getString(R.string.mode_tap_long), getString(R.string.mode_button_long))
        AlertDialog.Builder(this)
            .setTitle(R.string.mode_title)
            .setSingleChoiceItems(labels, canvas.hoverMode.ordinal) { d, which ->
                canvas.hoverMode = modes[which]
                save { putInt(KEY_MODE, which) }
                refreshToolbar()
                d.dismiss()
            }
            .show()
    }

    private fun chooseColor() {
        val grid = GridLayout(this).apply {
            columnCount = 6
            setPadding((16 * dp).roundToInt(), (8 * dp).roundToInt(), (16 * dp).roundToInt(), 0)
        }
        var dialog: AlertDialog? = null
        val size = (44 * dp).roundToInt()
        PALETTE.forEach { c ->
            val swatch = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    val selected = c == canvas.color
                    setStroke(((if (selected) 4 else 1) * dp).roundToInt(), if (selected) getColor(R.color.accent) else Color.argb(80, 128, 128, 128))
                }
                setOnClickListener {
                    canvas.color = c
                    setEraser(false)
                    save { putInt(KEY_COLOR, c) }
                    refreshToolbar()
                    dialog?.dismiss()
                }
            }
            grid.addView(swatch, GridLayout.LayoutParams().apply {
                width = size
                height = size
                setMargins((6 * dp).roundToInt(), (6 * dp).roundToInt(), (6 * dp).roundToInt(), (6 * dp).roundToInt())
            })
        }
        dialog = AlertDialog.Builder(this).setTitle(R.string.color).setView(grid).show()
    }

    private fun widthKey(t: DrawingView.Tool) = "width_dp_" + t.name
    private fun opacityKey(t: DrawingView.Tool) = "opacity_" + t.name

    /** Loads the selected pen's own size and opacity. */
    private fun applyToolSettings() {
        val t = canvas.tool
        val (defWidth, defOpacity) = TOOL_DEFAULTS.getValue(t)
        canvas.brushWidth = prefs.getFloat(widthKey(t), defWidth) * dp
        canvas.opacity = prefs.getInt(opacityKey(t), defOpacity)
    }

    private fun sliderRow(parent: LinearLayout, title: Int, max: Int, value: Int, format: (Int) -> String, onChange: (Int) -> Unit) {
        val label = TextView(this).apply {
            textSize = 14f
            setPadding(0, (12 * dp).roundToInt(), 0, 0)
        }
        fun update(v: Int) { label.text = getString(title) + "  ·  " + format(v) }
        update(value)
        val bar = SeekBar(this).apply {
            this.max = max
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    update(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        parent.addView(label)
        parent.addView(bar)
    }

    /** Size slider position (half-dp steps from 0.5 dp) ↔ px. */
    private fun sizeToProgress(px: Float) = ((px / dp) * 2 - 1).roundToInt().coerceIn(0, MAX_SIZE_DP * 2 - 1)
    private fun progressToDp(v: Int) = (v + 1) / 2f
    private fun dpLabel(v: Int) = "${progressToDp(v)} dp"

    private fun chooseTool() {
        val pad = (20 * dp).roundToInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * dp).roundToInt(), pad, 0)
        }
        val preview = DrawingView(this).apply {
            inputEnabled = false
            background = canvas.background
            steadiness = 0f
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 12 * dp)
            }
        }
        fun refreshPreview() {
            preview.tool = canvas.tool
            preview.color = canvas.color
            preview.brushWidth = canvas.brushWidth
            preview.opacity = canvas.opacity
            preview.showDemo()
        }

        // Row of pens.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val cells = HashMap<DrawingView.Tool, View>()
        lateinit var sliders: LinearLayout
        fun buildSliders() {
            sliders.removeAllViews()
            val t = canvas.tool
            sliderRow(sliders, R.string.size, MAX_SIZE_DP * 2 - 1, sizeToProgress(canvas.brushWidth), ::dpLabel) { v ->
                canvas.brushWidth = progressToDp(v) * dp
                save { putFloat(widthKey(t), progressToDp(v)) }
                refreshPreview()
            }
            sliderRow(sliders, R.string.opacity, 100, canvas.opacity, { "$it%" }) { v ->
                canvas.opacity = v
                save { putInt(opacityKey(t), v) }
                refreshPreview()
            }
        }
        fun select(t: DrawingView.Tool) {
            canvas.tool = t
            save { putInt(KEY_TOOL, t.ordinal) }
            applyToolSettings()
            cells.forEach { (k, v) -> v.background = if (k == t) getDrawable(R.drawable.chip_bg) else null }
            buildSliders()
            refreshPreview()
            refreshToolbar()
        }
        DrawingView.Tool.entries.forEach { t ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, (8 * dp).roundToInt(), 0, (8 * dp).roundToInt())
                isClickable = true
                addView(android.widget.ImageView(this@MainActivity).apply { setImageResource(TOOL_ICONS.getValue(t)) })
                addView(TextView(this@MainActivity).apply {
                    setText(TOOL_NAMES.getValue(t))
                    textSize = 12f
                    gravity = Gravity.CENTER
                })
                setOnClickListener { select(t) }
            }
            cells[t] = cell
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        box.addView(row)
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (110 * dp).roundToInt()).apply {
            topMargin = (8 * dp).roundToInt()
        })
        sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(sliders)
        select(canvas.tool)

        AlertDialog.Builder(this).setTitle(R.string.pens).setView(box).setPositiveButton(R.string.done, null).show()
    }

    private fun chooseEraserSize() {
        val pad = (20 * dp).roundToInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
        }
        sliderRow(box, R.string.size, MAX_SIZE_DP * 2 - 1, sizeToProgress(canvas.eraserWidth), ::dpLabel) { v ->
            canvas.eraserWidth = progressToDp(v) * dp
            save { putFloat(KEY_ERASER_DP, progressToDp(v)) }
        }
        AlertDialog.Builder(this).setTitle(R.string.eraser_size).setView(box).setPositiveButton(R.string.done, null).show()
    }

    private fun chooseSteadiness() {
        val hint = TextView(this).apply {
            setText(R.string.steadiness_hint)
            setPadding(0, 0, 0, (12 * dp).roundToInt())
        }
        val bar = SeekBar(this).apply {
            max = 90
            progress = (canvas.steadiness * 100).roundToInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { canvas.steadiness = v / 100f }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { save { putFloat(KEY_STEADY, canvas.steadiness) } }
            })
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).roundToInt(), (8 * dp).roundToInt(), (20 * dp).roundToInt(), 0)
            addView(hint)
            addView(bar)
        }
        AlertDialog.Builder(this).setTitle(R.string.steadiness).setView(box).setPositiveButton(R.string.done, null).show()
    }

    private fun chooseBackground() {
        val labels = arrayOf(getString(R.string.bg_paper), getString(R.string.bg_grid), getString(R.string.bg_dark))
        AlertDialog.Builder(this)
            .setTitle(R.string.background)
            .setSingleChoiceItems(labels, canvas.background.ordinal) { d, which ->
                val bg = DrawingView.Background.entries[which]
                canvas.background = bg
                // Keep the ink visible on the new background.
                if (bg == DrawingView.Background.DARK && canvas.color == Color.BLACK) canvas.color = Color.WHITE
                if (bg != DrawingView.Background.DARK && canvas.color == Color.WHITE) canvas.color = Color.BLACK
                save { putInt(KEY_BG, which); putInt(KEY_COLOR, canvas.color) }
                refreshToolbar()
                d.dismiss()
            }
            .show()
    }

    private fun showMore(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.steadiness)
            menu.add(0, 2, 1, R.string.background)
            menu.add(0, 3, 2, R.string.finger_draws).apply {
                isCheckable = true
                isChecked = canvas.fingerDraws
            }
            menu.add(0, 4, 3, R.string.save)
            menu.add(0, 5, 4, R.string.share)
            menu.add(0, 6, 5, R.string.clear)
            menu.add(0, 7, 6, R.string.help)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> chooseSteadiness()
                    2 -> chooseBackground()
                    3 -> {
                        canvas.fingerDraws = !canvas.fingerDraws
                        save { putBoolean(KEY_FINGER, canvas.fingerDraws) }
                    }
                    4 -> saveToGallery()
                    5 -> share()
                    6 -> {
                        canvas.clear()
                        toast(R.string.cleared)
                    }
                    7 -> showHelp()
                }
                true
            }
            show()
        }
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_body)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    // --- Export ------------------------------------------------------------------------------

    private fun fileName() = "HoverInk_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"

    private fun saveToGallery() {
        if (canvas.isEmpty) return toast(R.string.empty)
        val bmp = canvas.export()
        io.execute {
            val ok = runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName())
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Hover Ink")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("insert failed")
                contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }.isSuccess
            main.post { toast(if (ok) R.string.saved else R.string.save_failed) }
        }
    }

    private fun share() {
        if (canvas.isEmpty) return toast(R.string.empty)
        val bmp = canvas.export()
        io.execute {
            val name = fileName()
            val ok = runCatching {
                val dir = File(cacheDir, ShareProvider.DIR).apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }.isSuccess
            main.post {
                if (!ok) return@post toast(R.string.save_failed)
                val send = Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, ShareProvider.uriFor(name))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, getString(R.string.share)))
            }
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_BG = "background"
        private const val KEY_COLOR = "color"
        private const val KEY_WIDTH_DP = "width_dp"
        private const val KEY_TOOL = "tool"
        private const val KEY_ERASER_DP = "eraser_dp"
        private const val MAX_SIZE_DP = 40

        /** Default size (dp) and opacity (%) for each pen. */
        private val TOOL_DEFAULTS = mapOf(
            DrawingView.Tool.PEN to (3f to 100),
            DrawingView.Tool.PENCIL to (3f to 40),
            DrawingView.Tool.FOUNTAIN to (6f to 100),
            DrawingView.Tool.MARKER to (16f to 40),
            DrawingView.Tool.BRUSH to (8f to 100),
        )
        private val TOOL_ICONS = mapOf(
            DrawingView.Tool.PEN to R.drawable.ic_tool_pen,
            DrawingView.Tool.PENCIL to R.drawable.ic_tool_pencil,
            DrawingView.Tool.FOUNTAIN to R.drawable.ic_tool_fountain,
            DrawingView.Tool.MARKER to R.drawable.ic_tool_marker,
            DrawingView.Tool.BRUSH to R.drawable.ic_tool_brush,
        )
        private val TOOL_NAMES = mapOf(
            DrawingView.Tool.PEN to R.string.tool_pen,
            DrawingView.Tool.PENCIL to R.string.tool_pencil,
            DrawingView.Tool.FOUNTAIN to R.string.tool_fountain,
            DrawingView.Tool.MARKER to R.string.tool_marker,
            DrawingView.Tool.BRUSH to R.string.tool_brush,
        )
        private const val KEY_STEADY = "steadiness"
        private const val KEY_FINGER = "finger_draws"
        private const val KEY_SEEN_HELP = "seen_help"

        private val PALETTE = intArrayOf(
            Color.BLACK, Color.WHITE, Color.rgb(142, 142, 147), Color.rgb(255, 59, 48), Color.rgb(255, 149, 0), Color.rgb(255, 204, 0),
            Color.rgb(52, 199, 89), Color.rgb(0, 199, 190), Color.rgb(10, 132, 255), Color.rgb(88, 86, 214), Color.rgb(175, 82, 222), Color.rgb(255, 45, 85),
            Color.rgb(162, 132, 94), Color.rgb(0, 80, 60), Color.rgb(0, 40, 110), Color.rgb(110, 0, 30), Color.rgb(255, 182, 193), Color.rgb(173, 216, 230),
        )
    }
}
