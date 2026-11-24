package io.github.guanhui999.fakelocgum.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * The very same touch implementation is used in Compose and WindowManager.
 * Repair: keep a pointer captured until UP/CANCEL; a parent scroll/drag must not
 * steal the gesture, and a held pointer must not depend on new MOVE events.
 */
class JoystickPadView(context: Context) : View(context) {
    var onVector: (Float, Float) -> Unit = { _, _ -> }
    var onCancel: (() -> Unit)? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dx = 0f
    private var dy = 0f
    private var pointer = MotionEvent.INVALID_POINTER_ID

    init {
        contentDescription = "方向摇杆：上北下南左西右东，按住持续移动，松手停止"
        isClickable = true
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = min(cx, cy) * .82f
        paint.style = Paint.Style.FILL
        paint.color = if (isEnabled) 0xFF414D61.toInt() else 0xFF555555.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.color = Color.WHITE
        paint.textSize = 12f * resources.displayMetrics.scaledDensity
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText("北 ↑", cx, cy - radius + paint.textSize, paint)
        canvas.drawText("↓ 南", cx, cy + radius - 4f, paint)
        canvas.drawText("西", cx - radius + paint.textSize, cy + paint.textSize / 3, paint)
        canvas.drawText("东", cx + radius - paint.textSize, cy + paint.textSize / 3, paint)
        paint.color = if (isEnabled) 0xFF83B4FF.toInt() else 0xFF888888.toInt()
        canvas.drawCircle(cx + dx * radius * .64f, cy + dy * radius * .64f, radius * .27f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) { cancelInput(); return false }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointer = event.getPointerId(event.actionIndex)
                parent?.requestDisallowInterceptTouchEvent(true)
                Log.i(TAG, "DOWN window=${windowVisibility} size=${width}x$height")
                updateVector(event, event.actionIndex)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointer)
                if (index < 0) { cancelInput(); return true }
                parent?.requestDisallowInterceptTouchEvent(true)
                updateVector(event, index)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointer) cancelInput()
                return true
            }
            MotionEvent.ACTION_UP -> { cancelInput(graceful = true); performClick(); return true }
            MotionEvent.ACTION_CANCEL -> { cancelInput(); return true }
            MotionEvent.ACTION_POINTER_DOWN -> return true // first finger owns this gesture
        }
        return pointer != MotionEvent.INVALID_POINTER_ID
    }

    private fun updateVector(event: MotionEvent, index: Int) {
        val radius = (min(width, height) * .82f / 2f).coerceAtLeast(1f)
        dx = (event.getX(index) - width / 2f) / radius
        dy = (event.getY(index) - height / 2f) / radius
        val length = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        dx /= length
        dy /= length
        onVector(dx, dy)
        invalidate()
    }

    fun cancelInput(graceful: Boolean = false) {
        val hadPointer = pointer != MotionEvent.INVALID_POINTER_ID
        pointer = MotionEvent.INVALID_POINTER_ID
        dx = 0f
        dy = 0f
        parent?.requestDisallowInterceptTouchEvent(false)
        if (hadPointer) {
            if (!graceful && onCancel != null) onCancel?.invoke() else onVector(0f, 0f)
            Log.i(TAG, "UP/CANCEL neutral graceful=$graceful")
        }
        invalidate()
    }

    override fun setEnabled(enabled: Boolean) {
        if (!enabled && isEnabled) cancelInput()
        super.setEnabled(enabled)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { cancelInput(); super.onDetachedFromWindow() }

    private companion object { const val TAG = "FakeLocGum/JoystickTouch" }
}
