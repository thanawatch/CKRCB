package com.example.cookierunbot.automation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/** Displays the immutable captured image: interactions never reach the game underneath. */
internal class CalibrationImageView(
    context: Context,
    private val onPointChosen: (CalibrationPoint) -> Unit,
) : View(context) {
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        strokeWidth = 2f * resources.displayMetrics.density
        style = Paint.Style.STROKE
    }
    private var bitmap: Bitmap? = null
    private var point: CalibrationPoint? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false
    private var scaling = false
    private val slop = 8f * resources.displayMetrics.density
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoom = (zoom * detector.scaleFactor).coerceIn(1f, 6f)
            limitPan()
            invalidate()
            return true
        }
    })

    fun showFrame(frame: Bitmap) {
        bitmap = frame
        point = null
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    fun selectedPoint(): CalibrationPoint? = point

    fun select(point: CalibrationPoint) {
        this.point = point
        onPointChosen(point)
        invalidate()
    }

    fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(1f, 6f)
        limitPan()
        invalidate()
    }

    private fun baseScale(): Float = min(
        width.toFloat() / CalibrationCoordinatePolicy.WIDTH,
        height.toFloat() / CalibrationCoordinatePolicy.HEIGHT,
    )

    private fun imageScale() = baseScale() * zoom
    private fun imageLeft() = (width - CalibrationCoordinatePolicy.WIDTH * imageScale()) / 2f + panX
    private fun imageTop() = (height - CalibrationCoordinatePolicy.HEIGHT * imageScale()) / 2f + panY

    private fun limitPan() {
        val scale = imageScale()
        val maxX = ((CalibrationCoordinatePolicy.WIDTH * scale - width) / 2f).coerceAtLeast(0f)
        val maxY = ((CalibrationCoordinatePolicy.HEIGHT * scale - height) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val frame = bitmap ?: return
        val scale = imageScale()
        val left = imageLeft()
        val top = imageTop()
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        canvas.drawBitmap(frame, null, android.graphics.RectF(
            left, top,
            left + CalibrationCoordinatePolicy.WIDTH * scale,
            top + CalibrationCoordinatePolicy.HEIGHT * scale,
        ), imagePaint)
        point?.let { p ->
            val cx = left + p.x * scale
            val cy = top + p.y * scale
            val radius = 11f * resources.displayMetrics.density
            canvas.drawCircle(cx, cy, radius, markerPaint)
            canvas.drawLine(cx - radius * 1.6f, cy, cx + radius * 1.6f, cy, markerPaint)
            canvas.drawLine(cx, cy - radius * 1.6f, cx, cy + radius * 1.6f, markerPaint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bitmap == null) return false
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                dragged = false; scaling = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> { scaling = true; dragged = true; return true }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || pinch.isInProgress) { scaling = true; dragged = true; return true }
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) dragged = true
                if (dragged && !scaling) {
                    panX += event.x - lastX; panY += event.y - lastY
                    limitPan(); invalidate()
                }
                lastX = event.x; lastY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragged && !scaling) {
                    CalibrationCoordinatePolicy.fromImageTouch(
                        event.x, event.y, imageLeft(), imageTop(), imageScale(),
                    )?.let { point = it; onPointChosen(it); invalidate() }
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }
}
