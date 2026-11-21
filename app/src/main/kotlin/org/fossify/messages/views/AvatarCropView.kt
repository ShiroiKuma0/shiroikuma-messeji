package org.fossify.messages.views

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import kotlin.math.max
import kotlin.math.min

/**
 * A picture under a round window: a drag moves it, a pinch zooms it, and it can never leave the
 * circle uncovered. [crop] gives the square around the circle as it is shown.
 */
@SuppressLint("ViewConstructor")
class AvatarCropView(
    context: Context,
    private val picture: Bitmap,
    scrimColor: Int,
    ringColor: Int,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val matrix = Matrix()
    private val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(scrimColor, SCRIM_ALPHA)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ringColor
        style = Paint.Style.STROKE
        strokeWidth = RING_DP * density
    }
    private val scrim = Path().apply { fillType = Path.FillType.EVEN_ODD }

    private var circleX = 0f
    private var circleY = 0f
    private var radius = 0f
    private var minScale = 1f
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val pinch = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val target = (scale * detector.scaleFactor).coerceIn(minScale, minScale * MAX_ZOOM)
                val factor = target / scale
                // zoom about the fingers: the point under them stays under them
                offsetX = detector.focusX - (detector.focusX - offsetX) * factor
                offsetY = detector.focusY - (detector.focusY - offsetY) * factor
                scale = target
                settle()
                return true
            }
        },
    )

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val tallest = (resources.displayMetrics.heightPixels * MAX_HEIGHT_SHARE).toInt()
        setMeasuredDimension(width, min(width, tallest))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        circleX = w / 2f
        circleY = h / 2f
        radius = min(w, h) / 2f - MARGIN_DP * density
        minScale = max(2 * radius / picture.width, 2 * radius / picture.height)
        scale = minScale
        offsetX = circleX - picture.width * scale / 2
        offsetY = circleY - picture.height * scale / 2
        scrim.reset()
        scrim.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        scrim.addCircle(circleX, circleY, radius, Path.Direction.CW)
        settle()
    }

    // Keep the circle covered, then redraw.
    private fun settle() {
        val width = picture.width * scale
        val height = picture.height * scale
        offsetX = offsetX.coerceIn(circleX + radius - width, circleX - radius)
        offsetY = offsetY.coerceIn(circleY + radius - height, circleY - radius)
        matrix.setScale(scale, scale)
        matrix.postTranslate(offsetX, offsetY)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        pinch.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                dragging = true
            }

            // a second finger starts a pinch; the drag resumes from the finger that stays
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> dragging = false

            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 1 && !pinch.isInProgress) {
                if (dragging) {
                    offsetX += event.x - lastX
                    offsetY += event.y - lastY
                    settle()
                }
                lastX = event.x
                lastY = event.y
                dragging = true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawBitmap(picture, matrix, picturePaint)
        canvas.drawPath(scrim, scrimPaint)
        canvas.drawCircle(circleX, circleY, radius, ringPaint)
    }

    /** The square around the circle, as shown, [size] pixels a side. */
    fun crop(size: Int): Bitmap {
        val result = createBitmap(size, size)
        val toResult = Matrix(matrix).apply {
            postTranslate(radius - circleX, radius - circleY)
            postScale(size / (2 * radius), size / (2 * radius))
        }
        Canvas(result).drawBitmap(picture, toResult, picturePaint)
        return result
    }

    companion object {
        private const val MAX_ZOOM = 8f
        private const val MARGIN_DP = 12f
        private const val RING_DP = 2f
        private const val SCRIM_ALPHA = 170
        private const val MAX_HEIGHT_SHARE = 0.5f
    }
}
