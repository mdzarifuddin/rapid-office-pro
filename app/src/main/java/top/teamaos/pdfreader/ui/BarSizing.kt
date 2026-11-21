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

    private const val PEEK_DP = 24

    /** The bar's own side margin and padding (see activity_reader.xml), on each side. */
    private const val BAR_SIDE_DP = 4 + 4

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

    /**
     * Width that shows as many whole buttons as the screen has room for, plus a peek of the next;
     * 0 before they are measured. As wide as the phone allows, so as many tools as possible are
     * in view at once.
     */
    private fun firstButtonsWidth(row: LinearLayout): Int {
        val metrics = row.resources.displayMetrics
        val available = metrics.widthPixels - (2 * BAR_SIDE_DP * metrics.density).toInt()
        val peek = (PEEK_DP * metrics.density).toInt()

        val visible = (0 until row.childCount).map(row::getChildAt).filter { it.visibility != View.GONE }
        if (visible.any { it.width == 0 }) return 0
        fun widthOf(child: View): Int {
            val margins = child.layoutParams as? ViewGroup.MarginLayoutParams
            return child.width + (margins?.leftMargin ?: 0) + (margins?.rightMargin ?: 0)
        }

        val everything = row.paddingLeft + row.paddingRight + visible.sumOf(::widthOf)
        // Everything fits: no need to cut the bar at all.
        if (everything <= available) return ViewGroup.LayoutParams.WRAP_CONTENT

        var width = row.paddingLeft
        var best = width
        for (child in visible) {
            width += widthOf(child)
            if (width + peek > available) break
            best = width + peek
        }
        return best
    }
}
