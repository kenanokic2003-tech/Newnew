package com.keno.billiards.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Where the game's spin ball (the little white ball with a red dot) is, as fractions of the screen. */
data class SpinWidget(val cx: Double, val cy: Double, val r: Double) {
    /** Convert to frame pixels: cx,cy relative to frame width/height, r relative to frame height. */
    fun toPx(w: Int, h: Int) = SpinWidgetPx(cx * w, cy * h, r * h)
}

data class SpinWidgetPx(val cx: Double, val cy: Double, val r: Double)

/**
 * Spin as read from the on-screen spin ball. x: +right, y: +up(top spin / follow), both -1..1
 * where 1 means the red dot is at the rim of the spin ball. [found] is false when no red dot could
 * be seen, in which case x and y are 0 and MUST NOT be treated as a measurement.
 */
class SpinReading(val x: Double, val y: Double, val found: Boolean, val redPixels: Int)

object SpinReader {

    private fun isRed(p: Int): Boolean {
        val h = Hsv.h(p)
        return (h <= 14 || h >= 346) && Hsv.s(p) >= 85 && Hsv.v(p) >= 150
    }

    /** Read the red-dot offset inside a known spin ball. */
    fun read(f: Frame, wdg: SpinWidgetPx): SpinReading {
        val rr = wdg.r * 1.15
        val x0 = max(0, (wdg.cx - rr).toInt()); val x1 = min(f.width - 1, (wdg.cx + rr).toInt())
        val y0 = max(0, (wdg.cy - rr).toInt()); val y1 = min(f.height - 1, (wdg.cy + rr).toInt())
        var sx = 0.0; var sy = 0.0; var n = 0
        for (y in y0..y1) for (x in x0..x1) {
            if (hypot(x - wdg.cx, y - wdg.cy) > rr) continue
            if (isRed(f.hsv[y * f.width + x])) { sx += x; sy += y; n++ }
        }
        val minPx = max(3.0, 0.0015 * Math.PI * wdg.r * wdg.r).toInt()
        if (n < minPx) return SpinReading(0.0, 0.0, false, n)
        var dx = (sx / n - wdg.cx) / wdg.r
        var dy = (wdg.cy - sy / n) / wdg.r
        val m = hypot(dx, dy)
        if (m > 1.0) { dx /= m; dy /= m }
        return SpinReading(dx, dy, true, n)
    }

    /**
     * Best-effort search for the spin ball without user calibration: a roundish WHITE blob of a
     * plausible size that contains a red dot. Returns null if nothing like that exists.
     * This relies on the widget looking like "white ball + red dot"; if the game's look differs,
     * use manual calibration instead.
     */
    fun autoLocate(f: Frame, table: TableModel?): SpinWidgetPx? {
        val w = f.width; val h = f.height
        val visited = BooleanArray(w * h)
        val minR = 0.02 * h; val maxR = 0.10 * h
        val stack = IntArray(w * h)
        var best: SpinWidgetPx? = null
        var bestScore = 0.0
        for (start in 0 until w * h) {
            if (visited[start]) continue
            val p0 = f.hsv[start]
            if (!(Hsv.s(p0) <= 55 && Hsv.v(p0) >= 200)) continue
            // flood fill the white component
            var sp = 0
            stack[sp++] = start; visited[start] = true
            var minX = w; var maxX = 0; var minY = h; var maxY = 0; var area = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % w; val y = i / w
                area++
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                if (x > 0) { val j = i - 1; if (!visited[j] && isWhite(f.hsv[j])) { visited[j] = true; stack[sp++] = j } }
                if (x < w - 1) { val j = i + 1; if (!visited[j] && isWhite(f.hsv[j])) { visited[j] = true; stack[sp++] = j } }
                if (y > 0) { val j = i - w; if (!visited[j] && isWhite(f.hsv[j])) { visited[j] = true; stack[sp++] = j } }
                if (y < h - 1) { val j = i + w; if (!visited[j] && isWhite(f.hsv[j])) { visited[j] = true; stack[sp++] = j } }
            }
            val bw = maxX - minX + 1; val bh = maxY - minY + 1
            val rad = max(bw, bh) / 2.0
            if (rad < minR || rad > maxR) continue
            if (bw.toDouble() / bh !in 0.8..1.25) continue
            val disc = Math.PI * rad * rad
            if (area < 0.45 * disc) continue
            // red dot inside?
            val cx = (minX + maxX) / 2.0; val cy = (minY + maxY) / 2.0
            var red = 0
            for (y in minY..maxY) for (x in minX..maxX) {
                if (hypot(x - cx, y - cy) <= rad && isRed(f.hsv[y * w + x])) red++
            }
            if (red < 4 || red > 0.35 * area) continue
            val score = red.toDouble() * (area / disc)
            if (score > bestScore) { bestScore = score; best = SpinWidgetPx(cx, cy, rad) }
        }
        return best
    }

    private fun isWhite(p: Int) = Hsv.s(p) <= 55 && Hsv.v(p) >= 200
}
