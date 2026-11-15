package com.randomwalk.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.randomwalk.app.R
import kotlin.math.min

/**
 * Renders the white compass arrow plus the remaining distance in meters.
 * 0 degrees points up (negative Y); positive angles rotate the Canvas
 * clockwise. Only the arrow and the distance figure are drawn.
 */
class WalkScreenView(context: Context) : View(context) {

    var arrowAngleDegrees: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var distanceMeters: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 28f * resources.displayMetrics.density
    }

    private val arrowPath = Path()

    init {
        id = R.id.walk_screen
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height * 0.55f
        val scale = min(width, height).toFloat()

        drawDistance(canvas)

        canvas.save()
        canvas.rotate(arrowAngleDegrees, cx, cy)
        buildArrow(cx, cy, scale)
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()
    }

    private fun drawDistance(canvas: Canvas) {
        canvas.drawText("$distanceMeters m", width / 2f, height * 0.18f, textPaint)
    }

    private fun buildArrow(cx: Float, cy: Float, scale: Float) {
        val shaftHalf = scale * 0.025f
        val shaftLength = scale * 0.22f
        val headLength = scale * 0.10f
        val headHalf = scale * 0.055f

        // The arrow points up (negative Y) at 0 degrees: shaft below the center,
        // triangular head above it.
        val tipY = cy - shaftLength - headLength
        val shaftTopY = cy - shaftLength
        val headBaseY = cy - shaftLength

        arrowPath.reset()
        arrowPath.moveTo(cx - shaftHalf, cy + shaftLength)
        arrowPath.lineTo(cx - shaftHalf, shaftTopY)
        arrowPath.lineTo(cx - headHalf, headBaseY)
        arrowPath.lineTo(cx, tipY)
        arrowPath.lineTo(cx + headHalf, headBaseY)
        arrowPath.lineTo(cx + shaftHalf, shaftTopY)
        arrowPath.lineTo(cx + shaftHalf, cy + shaftLength)
        arrowPath.close()
    }
}
