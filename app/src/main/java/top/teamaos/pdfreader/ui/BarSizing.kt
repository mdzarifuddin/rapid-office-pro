package top.teamaos.pdfreader.ui

import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * The reading screens' bottom bar is one row of buttons.
 *
 * Sideways, the screen is wide enough for all of them and the bar simply fits its contents. Held
 * upright it is not, so the bar is cut to exactly the first [PORTRAIT_BUTTONS] buttons — plus a
 * sliver of the next, so it is plain there is more — and the rest are a swipe away. Sized from the
 * buttons' real widths rather than a guess, so labels in any language still line up on a button
 * edge.
 */
object BarSizing {

    private const val PORTRAIT_BUTTONS = 5
    private const val PEEK_DP = 18

    fun attach(scroll: HorizontalScrollView, row: LinearLayout) {
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit(scroll, row) }
    }

    private fun fit(scroll: HorizontalScrollView, row: LinearLayout) {
        val portrait = row.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        val wanted = if (portrait) firstButtonsWidth(row) else ViewGroup.LayoutParams.WRAP_CONTENT
        if (wanted == 0) return
        val params = scroll.layoutParams
        if (params.width == wanted) return
        params.width = wanted
        // Called from inside a layout pass; the change has to wait for the next one.
        scroll.post {
            scroll.layoutParams = params
            if (!portrait) scroll.scrollTo(0, 0)
        }
    }

    /** Width of the first buttons that are showing, plus the peek; 0 before they are measured. */
    private fun firstButtonsWidth(row: LinearLayout): Int {
        var width = row.paddingLeft
        var counted = 0
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (child.width == 0) return 0
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            width += child.width + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0)
            counted++
            if (counted == PORTRAIT_BUTTONS) {
                // Nothing further to reveal: no need to cut the bar at all.
                if (i == row.childCount - 1) return ViewGroup.LayoutParams.WRAP_CONTENT
                return width + (PEEK_DP * row.resources.displayMetrics.density).toInt()
            }
        }
        return ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
