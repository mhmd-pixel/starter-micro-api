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
    private lateinit var penBtn: ImageButton
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
        penBtn = findViewById(R.id.pen)
        eraserBtn = findViewById(R.id.eraser)
        colorDot = findViewById(R.id.colorDot)
        undoBtn = findViewById(R.id.undo)
        redoBtn = findViewById(R.id.redo)
        canvas.listener = this
        applyInsets()

        loadSettings()
        modeChip.setOnClickListener { chooseMode() }
        penBtn.setOnClickListener { setEraser(false) }
        eraserBtn.setOnClickListener { setEraser(true) }
        findViewById<View>(R.id.colorButton).setOnClickListener { chooseColor() }
        findViewById<View>(R.id.size).setOnClickListener { chooseSize() }
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
        canvas.brushWidth = prefs.getFloat(KEY_WIDTH_DP, 4f) * dp
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
        penBtn.background = if (!canvas.eraser) getDrawable(R.drawable.selected_bg) else null
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

    private fun chooseSize() {
        val maxDp = 40
        val preview = object : View(this) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: Canvas) {
                p.color = if (canvas.eraser) Color.GRAY else canvas.color
                c.drawCircle(width / 2f, height / 2f, canvas.brushWidth / 2f, p)
            }
        }
        val label = TextView(this).apply { gravity = Gravity.CENTER }
        fun updateLabel() { label.text = "${(canvas.brushWidth / dp * 10).roundToInt() / 10f} dp" }
        val bar = SeekBar(this).apply {
            max = maxDp * 2 - 1 // half-dp steps from 0.5 to 40
            progress = ((canvas.brushWidth / dp) * 2 - 1).roundToInt().coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) {
                    canvas.brushWidth = (v + 1) / 2f * dp
                    updateLabel()
                    preview.invalidate()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    save { putFloat(KEY_WIDTH_DP, canvas.brushWidth / dp) }
                }
            })
        }
        updateLabel()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).roundToInt(), (8 * dp).roundToInt(), (20 * dp).roundToInt(), 0)
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (maxDp * dp + 16 * dp).roundToInt()))
            addView(label)
            addView(bar)
        }
        AlertDialog.Builder(this).setTitle(R.string.size).setView(box).setPositiveButton(R.string.done, null).show()
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
