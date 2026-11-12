package app.hoverink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sin

/**
 * Draws every pen with simulated hover strokes and writes build/screenshots/pens.png.
 * Also checks that a low-opacity pencil gets darker with every pass.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class PenGalleryTest {
    private var t = 0L

    private fun newView(w: Int, h: Int) = DrawingView(RuntimeEnvironment.getApplication()).apply {
        measure(w, h)
        layout(0, 0, w, h)
        steadiness = 0f
        color = Color.rgb(30, 30, 34)
    }

    private fun hover(v: DrawingView, action: Int, x: Float, y: Float) {
        t += 8
        val p = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
        val c = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
        v.onHoverEvent(MotionEvent.obtain(0, t, action, 1, arrayOf(p), arrayOf(c), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0))
    }

    /** Back-and-forth scribble over [x0..x1] around [y], [passes] times, in one continuous stroke. */
    private fun scribble(v: DrawingView, x0: Float, x1: Float, y: Float, passes: Int, wave: Float = 0f) {
        hover(v, MotionEvent.ACTION_HOVER_ENTER, x0, y)
        for (pass in 0 until passes) {
            val n = 60
            for (i in 0..n) {
                val f = if (pass % 2 == 0) i / n.toFloat() else 1 - i / n.toFloat()
                val x = x0 + (x1 - x0) * f
                hover(v, MotionEvent.ACTION_HOVER_MOVE, x, y + wave * sin(f * 6.28f) + (pass % 3) * 2f)
            }
        }
        hover(v, MotionEvent.ACTION_HOVER_EXIT, x1, y)
    }

    private fun darkness(bmp: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int): Double {
        var sum = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) { sum += Color.alpha(bmp.getPixel(x, y)); n++ }
        return sum / n / 255.0
    }

    @Test
    fun pencilBuildsUpWithPasses() {
        val v = newView(1200, 600)
        v.tool = DrawingView.Tool.PENCIL
        v.opacity = 20
        v.brushWidth = 30f
        val rows = listOf(1, 2, 4, 8)
        rows.forEachIndexed { i, passes -> scribble(v, 100f, 1100f, 100f + i * 120f, passes) }
        val ink = v.export(includeBackground = false)
        val d = rows.indices.map { darkness(ink, 400, 800, 90 + it * 120, 110 + it * 120) }
        println("pencil 20% darkness by passes $rows: $d")
        for (i in 1 until d.size) assertTrue("more passes must be darker: $d", d[i] > d[i - 1] + 0.03)
        assertTrue("one pass at 20% should land near 20%: $d", d[0] in 0.13..0.30)
    }

    @Test
    fun renderGallery() {
        val w = 1233
        val rowH = 260
        val tools = listOf(
            Triple(DrawingView.Tool.PEN, 100, 9f),
            Triple(DrawingView.Tool.PENCIL, 20, 12f),
            Triple(DrawingView.Tool.PENCIL, 60, 12f),
            Triple(DrawingView.Tool.FOUNTAIN, 100, 22f),
            Triple(DrawingView.Tool.MARKER, 40, 48f),
            Triple(DrawingView.Tool.BRUSH, 100, 26f),
            Triple(DrawingView.Tool.PEN, 35, 14f),
        )
        val v = newView(w, rowH * tools.size)
        tools.forEachIndexed { i, (tool, op, width) ->
            v.tool = tool
            v.opacity = op
            v.brushWidth = width
            v.color = if (tool == DrawingView.Tool.MARKER) Color.rgb(255, 204, 0) else Color.rgb(30, 30, 34)
            val y = i * rowH + rowH / 2f
            // Left: one wavy pass. Right: back and forth 1, 2, 4 times.
            scribble(v, 60f, 520f, y, 1, wave = 70f)
            scribble(v, 600f, 760f, y, 1)
            scribble(v, 800f, 960f, y, 2)
            scribble(v, 1000f, 1180f, y, 4)
        }
        // Marker over the pen line shows it doesn't hide what's underneath.
        v.tool = DrawingView.Tool.MARKER; v.opacity = 40; v.brushWidth = 48f; v.color = Color.rgb(255, 204, 0)
        scribble(v, 60f, 520f, 0 * rowH + rowH / 2f, 1)
        val bmp = v.export()
        val c = Canvas(bmp)
        val labels = listOf("Pen 100%", "Pencil 20%", "Pencil 60%", "Fountain", "Marker 40%", "Brush", "Pen 35%")
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; color = Color.rgb(10, 132, 255) }
        labels.forEachIndexed { i, s -> c.drawText(s, 20f, i * rowH + 40f, paint) }
        File("build/screenshots").mkdirs()
        File("build/screenshots/pens.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
