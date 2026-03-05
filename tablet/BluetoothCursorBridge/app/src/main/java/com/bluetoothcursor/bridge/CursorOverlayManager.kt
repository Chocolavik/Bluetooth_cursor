package com.bluetoothcursor.bridge

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * CursorOverlayManager
 *
 * Manages a System Alert Window overlay that draws a visible cursor.
 * The cursor is rendered as a filled arrow pointer using Canvas.
 *
 * Call [create] to attach the overlay, [moveCursor] to update position,
 * and [destroy] to remove it.
 */
class CursorOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "CursorOverlay"
        const val TABLET_WIDTH  = 2560
        const val TABLET_HEIGHT = 1600
        private const val CURSOR_SIZE = 48  // px
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var cursorView: CursorView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // Current cursor position in screen pixels
    @Volatile var cursorX: Float = TABLET_WIDTH  / 2f
    @Volatile var cursorY: Float = TABLET_HEIGHT / 2f

    fun create() {
        if (cursorView != null) return

        val view = CursorView(context)
        val params = WindowManager.LayoutParams(
            CURSOR_SIZE,
            CURSOR_SIZE,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            x = cursorX.toInt()
            y = cursorY.toInt()
        }

        cursorView  = view
        layoutParams = params

        try {
            windowManager.addView(view, params)
            Log.i(TAG, "Cursor overlay created at ($cursorX, $cursorY)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view: $e")
        }
    }

    fun moveCursor(dx: Int, dy: Int) {
        cursorX = (cursorX + dx).coerceIn(0f, (TABLET_WIDTH  - CURSOR_SIZE).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (TABLET_HEIGHT - CURSOR_SIZE).toFloat())

        val params = layoutParams ?: return
        val view   = cursorView   ?: return

        params.x = cursorX.toInt()
        params.y = cursorY.toInt()

        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update overlay: $e")
        }
    }

    fun destroy() {
        val view = cursorView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {}
        cursorView   = null
        layoutParams = null
        Log.i(TAG, "Cursor overlay destroyed.")
    }

    // ── Inner View ────────────────────────────────────────────────────────────

    inner class CursorView(context: Context) : View(context) {

        private val arrowPath = Path()
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }

        init { setBackgroundColor(Color.TRANSPARENT) }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val s = CURSOR_SIZE.toFloat()

            // Classic arrow cursor shape (pointing top-left)
            arrowPath.reset()
            arrowPath.moveTo(0f, 0f)           // tip
            arrowPath.lineTo(0f, s * 0.85f)    // left side bottom
            arrowPath.lineTo(s * 0.28f, s * 0.60f)  // inner notch
            arrowPath.lineTo(s * 0.50f, s * 1.0f)   // tail bottom (clipped)
            arrowPath.lineTo(s * 0.63f, s * 0.92f)  // tail right
            arrowPath.lineTo(s * 0.40f, s * 0.52f)  // inner notch right
            arrowPath.lineTo(s * 0.70f, s * 0.52f)  // right side
            arrowPath.close()

            canvas.drawPath(arrowPath, fillPaint)
            canvas.drawPath(arrowPath, strokePaint)
        }
    }
}
