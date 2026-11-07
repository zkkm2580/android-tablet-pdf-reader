package com.example.tabletpdfreader.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min

/**
 * 支持平板手势缩放与平移的 PDF 页面视图：
 * - 1.0x 默认状态下允许父容器 ViewPager2 拦截左右/上下滑动翻页手势
 * - 双指捏合可无级缩放（1.0x ~ 4.0x），双击可快速在 1.0x 与 2.25x 间切换
 * - 放大状态下支持自由拖拽平移查看 PDF 细节
 */
class ZoomablePageImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val baseMatrix = Matrix()
    private val suppMatrix = Matrix()
    private val drawMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val displayRect = RectF()

    private val minScale = 1.0f
    private val midScale = 2.25f
    private val maxScale = 4.0f

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    var onSingleTapListener: (() -> Unit)? = null

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val currentScale = getScale()
                var scaleFactor = detector.scaleFactor
                val targetScale = currentScale * scaleFactor
                if (targetScale > maxScale) {
                    scaleFactor = maxScale / currentScale
                } else if (targetScale < minScale) {
                    scaleFactor = minScale / currentScale
                }
                suppMatrix.postScale(scaleFactor, scaleFactor, detector.focusX, detector.focusY)
                checkAndDisplayMatrix()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTapListener?.invoke()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val current = getScale()
                if (current < midScale - 0.15f) {
                    val factor = midScale / current
                    suppMatrix.postScale(factor, factor, e.x, e.y)
                } else {
                    suppMatrix.reset()
                }
                checkAndDisplayMatrix()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        configureBaseMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        configureBaseMatrix()
    }

    fun resetZoom() {
        suppMatrix.reset()
        checkAndDisplayMatrix()
    }

    private fun configureBaseMatrix() {
        val d = drawable ?: return
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val drawableWidth = d.intrinsicWidth.toFloat()
        val drawableHeight = d.intrinsicHeight.toFloat()
        if (drawableWidth <= 0f || drawableHeight <= 0f) return

        baseMatrix.reset()
        val scale = min(viewWidth / drawableWidth, viewHeight / drawableHeight)
        val dx = (viewWidth - drawableWidth * scale) / 2f
        val dy = (viewHeight - drawableHeight * scale) / 2f

        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate(dx, dy)
        suppMatrix.reset()
        applyDrawMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawable == null) return super.onTouchEvent(event)

        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)

        val currentScale = getScale()
        val zoomed = currentScale > 1.03f || event.pointerCount > 1
        parent?.requestDisallowInterceptTouchEvent(zoomed)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && currentScale > 1.03f && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (!isDragging) {
                        isDragging = (dx * dx + dy * dy) > 16f
                    }
                    if (isDragging) {
                        suppMatrix.postTranslate(dx, dy)
                        checkAndDisplayMatrix()
                        val rect = getDisplayRect(getDrawMatrix())
                        if (rect != null) {
                            val atHorizontalEdge =
                                (dx > 6f && rect.left >= -2f) || (dx < -6f && rect.right <= width + 2f)
                            val atVerticalEdge =
                                (dy > 6f && rect.top >= -2f) || (dy < -6f && rect.bottom <= height + 2f)
                            if (atHorizontalEdge || atVerticalEdge) {
                                parent?.requestDisallowInterceptTouchEvent(false)
                            }
                        }
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                if (getScale() < minScale) {
                    resetZoom()
                }
            }
        }
        return true
    }

    private fun getScale(): Float {
        suppMatrix.getValues(matrixValues)
        val sx = matrixValues[Matrix.MSCALE_X]
        return max(minScale, sx)
    }

    private fun checkAndDisplayMatrix() {
        if (checkMatrixBounds()) {
            applyDrawMatrix()
        }
    }

    private fun applyDrawMatrix() {
        imageMatrix = getDrawMatrix()
    }

    private fun getDrawMatrix(): Matrix {
        drawMatrix.set(baseMatrix)
        drawMatrix.postConcat(suppMatrix)
        return drawMatrix
    }

    private fun checkMatrixBounds(): Boolean {
        val rect = getDisplayRect(getDrawMatrix()) ?: return false
        val height = rect.height()
        val width = rect.width()
        var deltaX = 0f
        var deltaY = 0f

        val viewHeight = getHeight().toFloat()
        when {
            height <= viewHeight -> {
                deltaY = (viewHeight - height) / 2f - rect.top
            }
            rect.top > 0 -> {
                deltaY = -rect.top
            }
            rect.bottom < viewHeight -> {
                deltaY = viewHeight - rect.bottom
            }
        }

        val viewWidth = getWidth().toFloat()
        when {
            width <= viewWidth -> {
                deltaX = (viewWidth - width) / 2f - rect.left
            }
            rect.left > 0 -> {
                deltaX = -rect.left
            }
            rect.right < viewWidth -> {
                deltaX = viewWidth - rect.right
            }
        }

        suppMatrix.postTranslate(deltaX, deltaY)
        return true
    }

    private fun getDisplayRect(matrix: Matrix): RectF? {
        val d = drawable ?: return null
        displayRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        matrix.mapRect(displayRect)
        return displayRect
    }
}
