package com.khmerdubber.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/** 3×3 spots shaped like the video. Tap one to put the logo or lower third there. */
class PositionPad(ctx: Context) : View(ctx) {
    var vertical = false
        set(v) { field = v; requestLayout(); invalidate() }
    var position: () -> Pair<Float, Float> = { 0f to 0f }
    var onPick: (Float, Float) -> Unit = { _, _ -> }

    private val d = resources.displayMetrics.density
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = if (vertical) 66 to 112 else 112 to 66
        setMeasuredDimension((w * d).roundToInt(), (h * d).roundToInt())
    }

    private fun cellCenter(i: Int, size: Float, inset: Float) = inset + i * (size - 2 * inset) / 2f

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = RectF(d, d, w - d, h - d)
        frame.style = Paint.Style.FILL
        frame.color = 0x14FFFFFF
        c.drawRoundRect(r, 10 * d, 10 * d, frame)
        frame.style = Paint.Style.STROKE
        frame.strokeWidth = d
        frame.color = 0x40FFFFFF
        c.drawRoundRect(r, 10 * d, 10 * d, frame)

        val inset = 14 * d
        val (px, py) = position()
        var onSpot = false
        for (row in 0..2) for (col in 0..2) {
            val x = cellCenter(col, w, inset)
            val y = cellCenter(row, h, inset)
            val chosen = abs(px - col / 2f) < 0.02f && abs(py - row / 2f) < 0.02f
            if (chosen) onSpot = true
            dot.style = Paint.Style.FILL
            dot.color = if (chosen) context.getColor(R.color.accent) else 0x59FFFFFF
            c.drawCircle(x, y, if (chosen) 6.5f * d else 3f * d, dot)
        }
        if (!onSpot) {
            // moved by hand: show where it is now
            dot.style = Paint.Style.STROKE
            dot.strokeWidth = 2 * d
            dot.color = context.getColor(R.color.accent)
            c.drawCircle(inset + px * (w - 2 * inset), inset + py * (h - 2 * inset), 6f * d, dot)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val col = (e.x / width * 3).toInt().coerceIn(0, 2)
                val row = (e.y / height * 3).toInt().coerceIn(0, 2)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onPick(col / 2f, row / 2f)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(e)
    }
}
