package top.teamaos.pdfreader.core

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One brush stroke drawn over a page.
 *
 * [points] are x, y pairs in the page's own coordinates — points, origin bottom-left — the same
 * space search hits and links use. Storing them that way rather than in screen pixels is what lets
 * a stroke survive zooming, rotating the phone, cropping the page and being reopened months later
 * on a different screen: the page is the thing the reader drew on, not the display.
 */
class Stroke(
    /**
     * The row this stroke has in the database.
     *
     * Mutable, and negative until the row exists: a stroke has to appear under the finger the
     * instant it is lifted, which is before the insert has come back with a number.
     */
    var id: Long,
    val page: Int,
    val colour: Int,
    val widthPts: Float,
    val points: FloatArray,
) {
    val isEmpty: Boolean get() = points.size < 2

    /** Shortest distance from [x], [y] to this stroke, in page points. Used by the eraser. */
    fun distanceTo(x: Float, y: Float): Float {
        if (points.size < 2) return Float.MAX_VALUE
        if (points.size == 2) return hypot(x - points[0], y - points[1])
        var best = Float.MAX_VALUE
        var index = 0
        while (index + 3 < points.size) {
            val distance = distanceToSegment(
                x, y,
                points[index], points[index + 1],
                points[index + 2], points[index + 3],
            )
            if (distance < best) best = distance
            index += 2
        }
        return best
    }

    private fun distanceToSegment(
        x: Float,
        y: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
    ): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.0001f) return hypot(x - x1, y - y1)
        val along = (((x - x1) * dx + (y - y1) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(x - (x1 + along * dx), y - (y1 + along * dy))
    }

    private fun hypot(dx: Float, dy: Float): Float = kotlin.math.sqrt(dx * dx + dy * dy)

    fun toBytes(): ByteArray {
        val buffer = ByteBuffer.allocate(points.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        points.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    companion object {
        fun fromBytes(
            id: Long,
            page: Int,
            colour: Int,
            widthPts: Float,
            bytes: ByteArray,
        ): Stroke? {
            if (bytes.size < 8 || bytes.size % 4 != 0) return null
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val values = FloatArray(bytes.size / 4) { buffer.getFloat() }
            return Stroke(id, page, colour, widthPts, values)
        }

        /**
         * Thin a freshly drawn stroke down to the points that matter.
         *
         * A finger dragged across the screen produces a point every few milliseconds, most of them
         * a fraction of a millimetre from the last. Keeping all of them makes a stroke that is
         * slow to draw, large to store and no more accurate; dropping the ones that lie on the
         * line between their neighbours costs nothing visible.
         */
        fun simplify(points: List<Float>, tolerancePts: Float): FloatArray {
            if (points.size <= 4) return points.toFloatArray()
            val kept = ArrayList<Float>(points.size)
            kept.add(points[0])
            kept.add(points[1])
            var anchorX = points[0]
            var anchorY = points[1]
            var index = 2
            while (index + 1 < points.size - 2) {
                val x = points[index]
                val y = points[index + 1]
                val dx = x - anchorX
                val dy = y - anchorY
                if (dx * dx + dy * dy >= tolerancePts * tolerancePts) {
                    kept.add(x)
                    kept.add(y)
                    anchorX = x
                    anchorY = y
                }
                index += 2
            }
            kept.add(points[points.size - 2])
            kept.add(points[points.size - 1])
            return kept.toFloatArray()
        }
    }
}
