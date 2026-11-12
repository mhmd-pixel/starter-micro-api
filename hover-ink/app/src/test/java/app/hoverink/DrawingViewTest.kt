package app.hoverink

import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Feeds simulated S Pen events into the canvas and checks which pixels get ink. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DrawingViewTest {
    private lateinit var view: DrawingView
    private var t = 0L
    private var lastUndo = false

    @Before
    fun setUp() {
        view = DrawingView(RuntimeEnvironment.getApplication())
        view.listener = object : DrawingView.Listener {
            override fun onPenState(hovering: Boolean, inking: Boolean) {}
            override fun onHistoryChanged(canUndo: Boolean, canRedo: Boolean) { lastUndo = canUndo }
        }
        view.measure(400, 400)
        view.layout(0, 0, 400, 400)
        view.color = Color.BLACK
        view.brushWidth = 8f
        view.steadiness = 0f
    }

    private fun ev(action: Int, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_STYLUS, buttons: Int = 0, pressure: Float = 0f): MotionEvent {
        t += 8
        val props = MotionEvent.PointerProperties().apply { id = 0; toolType = tool }
        val coords = MotionEvent.PointerCoords().apply { this.x = x; this.y = y; this.pressure = pressure }
        return MotionEvent.obtain(0, t, action, 1, arrayOf(props), arrayOf(coords), 0, buttons, 1f, 1f, 0, 0,
            if (tool == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS, 0)
    }

    private fun hoverLine(x0: Float, x1: Float, y: Float, buttons: Int = 0) {
        view.onHoverEvent(ev(MotionEvent.ACTION_HOVER_ENTER, x0, y, buttons = buttons))
        var x = x0
        while (x <= x1) {
            view.onHoverEvent(ev(MotionEvent.ACTION_HOVER_MOVE, x, y, buttons = buttons))
            x += 5f
        }
        view.onHoverEvent(ev(MotionEvent.ACTION_HOVER_EXIT, x1, y, buttons = buttons))
    }

    private fun tap(x: Float, y: Float) {
        view.onTouchEvent(ev(MotionEvent.ACTION_DOWN, x, y, pressure = 0.5f))
        view.onTouchEvent(ev(MotionEvent.ACTION_UP, x, y, pressure = 0.5f))
    }

    private fun inked(x: Int, y: Int): Boolean = Color.alpha(view.export(includeBackground = false).getPixel(x, y)) > 128

    @Test
    fun alwaysMode_hoveringDraws() {
        view.hoverMode = DrawingView.HoverMode.ALWAYS
        hoverLine(50f, 350f, 100f)
        assertTrue(inked(200, 100))
        assertFalse(inked(200, 200))
        assertTrue(lastUndo)
    }

    @Test
    fun leavingHoverRange_liftsThePen() {
        view.hoverMode = DrawingView.HoverMode.ALWAYS
        hoverLine(50f, 150f, 100f)
        hoverLine(250f, 350f, 100f)
        assertTrue(inked(100, 100))
        assertTrue(inked(300, 100))
        assertFalse("gap between strokes must stay empty", inked(200, 100))
    }

    @Test
    fun holdButtonMode_onlyDrawsWithButton() {
        view.hoverMode = DrawingView.HoverMode.HOLD_BUTTON
        hoverLine(50f, 350f, 100f)
        assertFalse(inked(200, 100))
        hoverLine(50f, 350f, 200f, buttons = MotionEvent.BUTTON_STYLUS_PRIMARY)
        assertTrue(inked(200, 200))
    }

    @Test
    fun tapToggleMode_tapStartsAndStopsHoverInk() {
        view.hoverMode = DrawingView.HoverMode.TAP_TOGGLE
        hoverLine(50f, 350f, 100f)
        assertFalse("off until tapped", inked(200, 100))

        tap(380f, 380f)
        assertTrue(view.isTapInkOn)
        assertFalse("a tap must not leave a dot", inked(380, 380))
        hoverLine(50f, 350f, 200f)
        assertTrue(inked(200, 200))

        tap(380f, 380f)
        assertFalse(view.isTapInkOn)
        hoverLine(50f, 350f, 300f)
        assertFalse(inked(200, 300))
    }

    @Test
    fun penTouchStillDraws_fingerDoesNot() {
        view.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 50f, 100f, pressure = 0.5f))
        for (x in 55..350 step 5) view.onTouchEvent(ev(MotionEvent.ACTION_MOVE, x.toFloat(), 100f, pressure = 0.5f))
        view.onTouchEvent(ev(MotionEvent.ACTION_UP, 350f, 100f, pressure = 0.5f))
        assertTrue(inked(200, 100))

        val f = MotionEvent.TOOL_TYPE_FINGER
        view.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 50f, 200f, tool = f, pressure = 0.5f))
        for (x in 55..350 step 5) view.onTouchEvent(ev(MotionEvent.ACTION_MOVE, x.toFloat(), 200f, tool = f, pressure = 0.5f))
        view.onTouchEvent(ev(MotionEvent.ACTION_UP, 350f, 200f, tool = f, pressure = 0.5f))
        assertFalse(inked(200, 200))
    }

    @Test
    fun eraserUndoRedoAndClear() {
        hoverLine(50f, 350f, 100f)
        view.eraser = true
        hoverLine(150f, 250f, 100f)
        assertFalse("erased", inked(200, 100))
        assertTrue(inked(100, 100))

        view.undo()
        assertTrue("undoing the eraser restores ink", inked(200, 100))
        view.redo()
        assertFalse(inked(200, 100))

        view.clear()
        assertFalse(inked(100, 100))
        assertTrue(view.isEmpty)
        view.undo()
        assertTrue("clear is undoable", inked(100, 100))
    }

    @Test
    fun brushSizeSetsStrokeWidth() {
        view.brushWidth = 30f
        hoverLine(50f, 350f, 100f)
        assertTrue(inked(200, 112))
        assertFalse(inked(200, 120))
        assertEquals(30f, view.brushWidth)
    }
}
