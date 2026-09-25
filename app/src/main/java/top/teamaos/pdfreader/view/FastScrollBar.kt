package top.teamaos.pdfreader.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The drag handle down the right edge of the reader.
 *
 * A thousand-page document needs a grab handle you can throw from page 3 to page 900. The handle
 * fades out a moment after you stop so it never sits on top of the text.
 *
 * It only moves the document when it is grabbed on purpose; see [onTouchEvent].
 *
 * Deliberately **narrow**. An earlier version was full-width so it could draw its own page-number
 * bubble, and that turned every fade frame into a full-screen composite — scrolling went from under
 * 2% dropped frames to over 90%. The bubble now lives in its own small view in the layout, and this
 * one stays a thin strip that costs almost nothing to redraw.
 */
class FastScrollBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE62C3038.toInt() }
    private val handleActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2478C8.toInt() }
    private val chevronPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Slim, and tight against the edge, so it covers as little of the page as possible.
    private val handleWidth = 18f * density
    private val handleHeight = 56f * density
    private val trackWidth = 3f * density
    private val edgeInset = 2f * density

    /** How far outside the drawn handle a finger may land and still take hold of it. */
    private val grabWidth = 34f * density
    private val grabSlopY = 14f * density
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    /** Finger went down on the handle; it becomes a drag only once it has actually moved. */
    private var armed = false
    private var downY = 0f

    /** Distance from the finger to the handle's top, so the handle never jumps to the finger. */
    private var grabOffset = 0f

    private val trackRect = RectF()
    private val handleRect = RectF()
    private val chevron = Path()

    /** Where the handle sits: 0 at the start of the document, 1 at the end. */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (field == clamped) return
            field = clamped
            updateGestureExclusion()
            invalidate()
        }

    /** Hidden entirely for documents short enough not to need a handle. */
    var isUsable: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            visibility = if (value) VISIBLE else GONE
            if (value) updateGestureExclusion() else ViewCompat.setSystemGestureExclusionRects(this, emptyList())
        }

    /** Reports the dragged position; [settled] is true on release. */
    var onSeek: ((fraction: Float, settled: Boolean) -> Unit)? = null

    /** Told when dragging starts and stops, and where the handle's centre is, for the bubble. */
    var onDragStateChanged: ((dragging: Boolean, handleCentreY: Float) -> Unit)? = null

    private var dragging = false

    /** 0 when fully faded out. Separate from View.alpha so the fade is driven from here. */
    private var visibility01 = 0f

    private var fadeAnimator: ValueAnimator? = null
    private val fadeOutRunnable = Runnable { animateVisibility(0f) }

    /** Y of the middle of the handle, in this view's coordinates. */
    val handleCentreY: Float
        get() = progress * max(0f, height - handleHeight) + handleHeight / 2f

    /**
     * Show the handle because the document moved, then fade it out again, so the reader always
     * knows where it is without the bar permanently covering the margin.
     */
    fun flash() {
        if (!isUsable) return
        removeCallbacks(fadeOutRunnable)
        animateVisibility(1f)
        if (!dragging) postDelayed(fadeOutRunnable, FADE_DELAY_MS)
    }

    private fun animateVisibility(target: Float) {
        // Already there: just make sure nothing is still animating away from it. This matters —
        // flash() is called on every page change, and during a fling that is every frame. Building
        // a fresh ValueAnimator each time was enough on its own to drop the frame rate.
        if (visibility01 == target) {
            fadeAnimator?.cancel()
            fadeAnimator = null
            return
        }
        if (fadeAnimator?.isRunning == true && fadeTarget == target) return
        fadeAnimator?.cancel()
        fadeTarget = target
        fadeAnimator = ValueAnimator.ofFloat(visibility01, target).apply {
            duration = if (target > 0f) FADE_IN_MS else FADE_OUT_MS
            addUpdateListener {
                visibility01 = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private var fadeTarget = 0f

    private val exclusionRect = Rect()
    private val exclusionRects = mutableListOf<Rect>()

    /**
     * Tell the system to keep its edge gestures off the handle.
     *
     * The handle lives against the right edge, which on a phone with gesture navigation is also
     * the back-swipe strip. Without this the system arbitrates the first moments of every drag:
     * the bar gets nothing, or an immediate CANCEL, and dragging only starts working once the
     * finger has been held still long enough for the back detector to give up — which is exactly
     * what "the slider does nothing unless I wait a second first" was.
     *
     * The system allows 200dp of exclusion per edge, so only a band around the handle is claimed
     * rather than the whole strip, and it moves with the handle.
     */
    private fun updateGestureExclusion() {
        if (width <= 0 || height <= 0) return
        val centre = handleCentreY
        // Handing the window a new rectangle is a binder call, and progress changes on every page
        // crossed during a fling. Redrawing the strip is cheap; telling the system about it is
        // not, so it is only told once the handle has actually moved somewhere different.
        if (abs(centre - lastExclusionCentre) < EXCLUSION_STEP_DP * density) return
        lastExclusionCentre = centre
        val half = handleHeight / 2f + EXCLUSION_MARGIN_DP * density
        exclusionRect.set(
            0,
            (centre - half).toInt().coerceAtLeast(0),
            width,
            (centre + half).toInt().coerceAtMost(height),
        )
        exclusionRects.clear()
        exclusionRects.add(exclusionRect)
        ViewCompat.setSystemGestureExclusionRects(this, exclusionRects)
    }

    private var lastExclusionCentre = Float.NEGATIVE_INFINITY

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGestureExclusion()
    }

    /**
     * The handle moves the document only when it is taken hold of on purpose.
     *
     * A reader's thumb brushing the right edge used to throw them hundreds of pages: any touch in
     * the strip, anywhere down it, jumped straight to that point. Now:
     *  - while the handle is faded out, touching it only brings it back; the touch goes on to the
     *    page as normal;
     *  - once it is showing, only a finger on the handle itself counts, and it drags from where it
     *    was grabbed rather than jumping to the finger;
     *  - nothing moves until the finger has travelled a touch-slop, so a tap does nothing at all.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isUsable) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!onHandle(event.x, event.y)) return false
                if (visibility01 < VISIBLE_ENOUGH) {
                    flash()
                    return false
                }
                armed = true
                downY = event.y
                grabOffset = event.y - progress * max(0f, height - handleHeight)
                removeCallbacks(fadeOutRunnable)
                animateVisibility(1f)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!armed) return false
                if (!dragging) {
                    if (abs(event.y - downY) < touchSlop) return true
                    dragging = true
                    invalidate()
                }
                updateFromTouch(event.y, settled = false)
                onDragStateChanged?.invoke(true, handleCentreY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!armed) return false
                armed = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragging) {
                    dragging = false
                    updateFromTouch(event.y, settled = true)
                    onDragStateChanged?.invoke(false, handleCentreY)
                }
                postDelayed(fadeOutRunnable, FADE_DELAY_MS)
                invalidate()
                return true
            }
        }
        return false
    }

    private fun onHandle(x: Float, y: Float): Boolean {
        if (x < width - edgeInset - grabWidth) return false
        val top = progress * max(0f, height - handleHeight)
        return y >= top - grabSlopY && y <= top + handleHeight + grabSlopY
    }

    private fun updateFromTouch(y: Float, settled: Boolean) {
        val travel = max(1f, height - handleHeight)
        progress = ((y - grabOffset) / travel).coerceIn(0f, 1f)
        onSeek?.invoke(progress, settled)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!isUsable || visibility01 <= 0.01f) return
        val alpha = (visibility01 * 255).roundToInt().coerceIn(0, 255)

        val centreX = width - edgeInset - handleWidth / 2f
        trackRect.set(
            centreX - trackWidth / 2f,
            handleHeight / 2f,
            centreX + trackWidth / 2f,
            height - handleHeight / 2f,
        )
        trackPaint.alpha = (alpha * 0.6f).roundToInt()
        canvas.drawRoundRect(trackRect, trackWidth, trackWidth, trackPaint)

        val top = progress * max(0f, height - handleHeight)
        handleRect.set(centreX - handleWidth / 2f, top, centreX + handleWidth / 2f, top + handleHeight)
        val paint = if (dragging) handleActivePaint else handlePaint
        paint.alpha = alpha
        val radius = handleWidth / 2f
        canvas.drawRoundRect(handleRect, radius, radius, paint)

        chevronPaint.alpha = alpha
        drawChevron(canvas, handleRect.centerX(), handleRect.centerY() - 9f * density, up = true)
        drawChevron(canvas, handleRect.centerX(), handleRect.centerY() + 9f * density, up = false)
    }

    private fun drawChevron(canvas: Canvas, centreX: Float, centreY: Float, up: Boolean) {
        val halfWidth = 4f * density
        val halfHeight = 2.5f * density
        val tipY = if (up) centreY - halfHeight else centreY + halfHeight
        val baseY = if (up) centreY + halfHeight else centreY - halfHeight
        chevron.reset()
        chevron.moveTo(centreX - halfWidth, baseY)
        chevron.lineTo(centreX, tipY)
        chevron.lineTo(centreX + halfWidth, baseY)
        canvas.drawPath(chevron, chevronPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(fadeOutRunnable)
        fadeAnimator?.cancel()
    }

    private companion object {
        /** Extra height claimed above and below the handle; the per-edge budget is 200dp. */
        const val EXCLUSION_MARGIN_DP = 56f

        /** How far the handle has to move before the system is told about it again. */
        const val EXCLUSION_STEP_DP = 24f
        const val FADE_DELAY_MS = 1400L

        /** Below this the handle is too faint to have been aimed at. */
        const val VISIBLE_ENOUGH = 0.5f
        const val FADE_IN_MS = 120L
        const val FADE_OUT_MS = 320L
    }
}
