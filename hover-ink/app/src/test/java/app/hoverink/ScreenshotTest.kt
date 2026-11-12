package app.hoverink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Renders the main screen after a hover stroke to build/screenshots/main.png, for eyeballing the layout. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @Test
    fun renderMainScreen() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // Dismiss the first-run help so the canvas is visible.
        org.robolectric.shadows.ShadowDialog.getLatestDialog()?.dismiss()
        val root = activity.window.decorView
        val canvas = activity.findViewById<DrawingView>(R.id.canvas)
        var t = 0L
        fun hover(action: Int, x: Float, y: Float) {
            t += 8
            val p = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_STYLUS }
            val c = MotionEvent.PointerCoords().apply { this.x = x; this.y = y }
            canvas.onHoverEvent(MotionEvent.obtain(0, t, action, 1, arrayOf(p), arrayOf(c), 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0))
        }
        hover(MotionEvent.ACTION_HOVER_ENTER, 200f, 600f)
        for (i in 0..120) {
            val x = 200f + i * 7f
            hover(MotionEvent.ACTION_HOVER_MOVE, x, 600f + 180f * kotlin.math.sin(i / 12.0).toFloat())
        }
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val out = File("build/screenshots").apply { mkdirs() }
        File(out, "main.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
