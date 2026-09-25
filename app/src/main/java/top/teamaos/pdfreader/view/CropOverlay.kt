package top.teamaos.pdfreader.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The crop frame: a rectangle dragged over the page to say what to keep.
 *
 * It knows nothing about documents. It is handed the page's rectangle on screen, and hands back
 * the part of it the reader chose, as fractions of that rectangle — which is the only form that
 * survives the page being turned, zoomed or measured differently afterwards.
 *
 * The frame is dragged by its corners and edges, or moved as a whole from the middle. Grab
 * distance is generous on purpose: a 1 px border is not something a thumb can catch, and the
 * alternative — tiny handles you have to aim at — is worse on a phone than a forgiving hit area.
 */
class CropOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val scrimPaint = Paint().apply { color = 0xAA000000.toInt() }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 1.6f * density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x66FFFFFF
        strokeWidth = 1f * density
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2478C8.toInt()
        strokeWidth = 3.5f * density
        strokeCap = Paint.Cap.ROUND
    }

    /** The page being cropped, in this view's coordinates. Nothing is drawn until it is set. */
    private val pageRect = RectF()

    /** The part of [pageRect] to keep. */
    private val cropRect = RectF()

    private val grab = 26f * density
    private val minSize = 48f * density
    private val handleArm = 18f * density

    private var dragMode = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    private enum class Drag { NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    val hasPage: Boolean get() = !pageRect.isEmpty

    /** Start cropping the page occupying [rect] on screen, with the whole page selected. */
    fun startFor(rect: RectF) {
        pageRect.set(rect)
        reset()
    }

    /** Back to the whole page. */
    fun reset() {
        cropRect.set(pageRect)
        invalidate()
    }

    /**
     * What the reader chose, as fractions of the page: left, top, right, bottom, all 0..1 and
     * measured from the page's top-left corner as it is drawn.
     */
    fun fractions(): RectF {
        if (pageRect.isEmpty) return RectF(0f, 0f, 1f, 1f)
        val width = max(1f, pageRect.width())
        val height = max(1f, pageRect.height())
        return RectF(
            ((cropRect.left - pageRect.left) / width).coerceIn(0f, 1f),
            ((cropRect.top - pageRect.top) / height).coerceIn(0f, 1f),
            ((cropRect.right - pageRect.left) / width).coerceIn(0f, 1f),
            ((cropRect.bottom - pageRect.top) / height).coerceIn(0f, 1f),
        )
    }

    /** True when the frame still covers the whole page, so applying it would change nothing. */
    val isWholePage: Boolean
        get() = abs(cropRect.left - pageRect.left) < 1f &&
            abs(cropRect.top - pageRect.top) < 1f &&
            abs(cropRect.right - pageRect.right) < 1f &&
            abs(cropRect.bottom - pageRect.bottom) < 1f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (pageRect.isEmpty) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragMode = modeAt(event.x, event.y)
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragMode == Drag.NONE) return true
                applyDrag(event.x - lastX, event.y - lastY)
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragMode = Drag.NONE
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun modeAt(x: Float, y: Float): Drag {
        val nearLeft = abs(x - cropRect.left) <= grab
        val nearRight = abs(x - cropRect.right) <= grab
        val nearTop = abs(y - cropRect.top) <= grab
        val nearBottom = abs(y - cropRect.bottom) <= grab
        val withinX = x >= cropRect.left - grab && x <= cropRect.right + grab
        val withinY = y >= cropRect.top - grab && y <= cropRect.bottom + grab
        return when {
            nearLeft && nearTop -> Drag.TOP_LEFT
            nearRight && nearTop -> Drag.TOP_RIGHT
            nearLeft && nearBottom -> Drag.BOTTOM_LEFT
            nearRight && nearBottom -> Drag.BOTTOM_RIGHT
            nearLeft && withinY -> Drag.LEFT
            nearRight && withinY -> Drag.RIGHT
            nearTop && withinX -> Drag.TOP
            nearBottom && withinX -> Drag.BOTTOM
            cropRect.contains(x, y) -> Drag.MOVE
            else -> Drag.NONE
        }
    }

    private fun applyDrag(dx: Float, dy: Float) {
        when (dragMode) {
            Drag.MOVE -> {
                val shiftX = dx.coerceIn(pageRect.left - cropRect.left, pageRect.right - cropRect.right)
                val shiftY = dy.coerceIn(pageRect.top - cropRect.top, pageRect.bottom - cropRect.bottom)
                cropRect.offset(shiftX, shiftY)
                return
            }
            Drag.NONE -> return
            else -> Unit
        }
        if (dragMode in setOf(Drag.LEFT, Drag.TOP_LEFT, Drag.BOTTOM_LEFT)) {
            cropRect.left = (cropRect.left + dx).coerceIn(pageRect.left, cropRect.right - minSize)
        }
        if (dragMode in setOf(Drag.RIGHT, Drag.TOP_RIGHT, Drag.BOTTOM_RIGHT)) {
            cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minSize, pageRect.right)
        }
        if (dragMode in setOf(Drag.TOP, Drag.TOP_LEFT, Drag.TOP_RIGHT)) {
            cropRect.top = (cropRect.top + dy).coerceIn(pageRect.top, cropRect.bottom - minSize)
        }
        if (dragMode in setOf(Drag.BOTTOM, Drag.BOTTOM_LEFT, Drag.BOTTOM_RIGHT)) {
            cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minSize, pageRect.bottom)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (pageRect.isEmpty) return

        // Everything outside the frame goes dark, so the kept area is the only lit thing on screen.
        canvas.drawRect(0f, 0f, width.toFloat(), cropRect.top, scrimPaint)
        canvas.drawRect(0f, cropRect.bottom, width.toFloat(), height.toFloat(), scrimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, scrimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width.toFloat(), cropRect.bottom, scrimPaint)

        val thirdX = cropRect.width() / 3f
        val thirdY = cropRect.height() / 3f
        for (step in 1..2) {
            val x = cropRect.left + thirdX * step
            val y = cropRect.top + thirdY * step
            canvas.drawLine(x, cropRect.top, x, cropRect.bottom, gridPaint)
            canvas.drawLine(cropRect.left, y, cropRect.right, y, gridPaint)
        }
        canvas.drawRect(cropRect, framePaint)

        val arm = min(handleArm, min(cropRect.width(), cropRect.height()) / 3f)
        drawCorner(canvas, cropRect.left, cropRect.top, arm, arm)
        drawCorner(canvas, cropRect.right, cropRect.top, -arm, arm)
        drawCorner(canvas, cropRect.left, cropRect.bottom, arm, -arm)
        drawCorner(canvas, cropRect.right, cropRect.bottom, -arm, -arm)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, armX: Float, armY: Float) {
        canvas.drawLine(x, y, x + armX, y, handlePaint)
        canvas.drawLine(x, y, x, y + armY, handlePaint)
    }
}
