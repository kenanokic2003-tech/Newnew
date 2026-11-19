package com.keno.billiards.core

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class BallKind { CUE, EIGHT, SOLID, STRIPE, UNKNOWN }

class Ball(
    val x: Double,
    val y: Double,
    val r: Double,
    val kind: BallKind,
    val whiteFrac: Double,
) {
    val pos get() = Vec2(x, y)
}

class BallDetection(val balls: List<Ball>, val radius: Double) {
    /** Index of the white cue ball in [balls], or -1. */
    val cueIndex: Int = balls.indexOfFirst { it.kind == BallKind.CUE }
    val cue: Ball? get() = if (cueIndex >= 0) balls[cueIndex] else null
}

/**
 * Finds balls as peaks of the Euclidean distance transform of the "not cloth" mask inside the
 * table. Every ball is a disc, so each one produces a peak whose height equals its radius.
 * That separates touching balls (a rack), and ignores thin things: the game's aim line,
 * the guide ring and the cue stick are all narrower than a ball and never form a peak.
 */
object BallDetector {

    fun detect(f: Frame, table: TableModel): BallDetection? {
        val w = f.width
        val hgt = f.height
        val rect = table.rect
        val x0 = max(0, rect.left.toInt() - 2)
        val y0 = max(0, rect.top.toInt() - 2)
        val x1 = min(w - 1, rect.right.toInt() + 2)
        val y1 = min(hgt - 1, rect.bottom.toInt() + 2)
        val rw = x1 - x0 + 1
        val rh = y1 - y0 + 1
        if (rw < 50 || rh < 30) return null

        val fg = BooleanArray(rw * rh)
        for (yy in 0 until rh) {
            val o = (y0 + yy) * w + x0
            for (xx in 0 until rw) fg[yy * rw + xx] = !table.isFelt(f.hsv[o + xx])
        }
        val edt = Edt.distance(fg, rw, rh)

        val tw = rect.width
        val minPeak = (tw / 110.0).toFloat()
        // local maxima of the distance transform
        class Peak(val x: Int, val y: Int, val v: Float)
        val peaks = ArrayList<Peak>()
        for (yy in 1 until rh - 1) for (xx in 1 until rw - 1) {
            val v = edt[yy * rw + xx]
            if (v < minPeak) continue
            var isMax = true
            loop@ for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                if (edt[(yy + dy) * rw + xx + dx] > v) { isMax = false; break@loop }
            }
            if (isMax) peaks.add(Peak(xx, yy, v))
        }
        if (peaks.isEmpty()) return BallDetection(emptyList(), tw / 32.0)

        // Keep only peaks away from pocket holes (a pocket's dark hole pokes into the rect corner).
        val rApprox = tw / 32.0
        val pocketPts = table.pockets
        fun nearPocket(px: Double, py: Double): Boolean {
            for (p in pocketPts) if (hypot(px - p.x, py - p.y) < 0.95 * rApprox) return true
            return false
        }

        // Non-maximum suppression (strongest first) with a small radius, to merge plateau duplicates.
        peaks.sortByDescending { it.v }
        val nms = ArrayList<Peak>()
        for (p in peaks) {
            var ok = true
            for (q in nms) {
                if (hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble()) < 0.7 * q.v) { ok = false; break }
            }
            if (ok) nms.add(p)
        }
        val plausible = nms.filter {
            it.v >= tw / 90.0 && it.v <= tw / 24.0 && !nearPocket((x0 + it.x).toDouble(), (y0 + it.y).toDouble())
        }
        if (plausible.isEmpty()) return BallDetection(emptyList(), tw / 32.0)

        // Ball radius = most common peak height (mode), robust to a stray big or small blob.
        var bestV = plausible[0].v
        var bestCnt = -1
        for (c in plausible) {
            var cnt = 0
            var sum = 0.0
            for (o in plausible) if (o.v >= c.v * 0.88f && o.v <= c.v * 1.12f) { cnt++; sum += o.v }
            if (cnt > bestCnt || (cnt == bestCnt && c.v > bestV)) { bestCnt = cnt; bestV = (sum / cnt).toFloat() }
        }
        val r = bestV + 0.5 // distance transform measures to the nearest background pixel centre

        // Accept peaks near that radius, enforce minimum separation of 1.5 r.
        val accepted = ArrayList<Peak>()
        for (p in plausible) {
            if (p.v + 0.5 < r * 0.78 || p.v + 0.5 > r * 1.3) continue
            var ok = true
            for (q in accepted) {
                if (hypot((p.x - q.x).toDouble(), (p.y - q.y).toDouble()) < 1.5 * r) { ok = false; break }
            }
            if (ok) accepted.add(p)
        }

        val balls = ArrayList<Ball>()
        for (p in accepted) {
            // refine the centre with the centroid of non-cloth pixels inside 0.95 r (2 iterations)
            var cx = (x0 + p.x).toDouble()
            var cy = (y0 + p.y).toDouble()
            for (it in 0 until 2) {
                var sx = 0.0; var sy = 0.0; var n = 0
                val ri = (r * 0.95).toInt() + 1
                for (dy in -ri..ri) for (dx in -ri..ri) {
                    val px = (cx + dx).toInt(); val py = (cy + dy).toInt()
                    if (px < x0 || px > x1 || py < y0 || py > y1) continue
                    if (hypot(px - cx, py - cy) > r * 0.95) continue
                    if (fg[(py - y0) * rw + (px - x0)]) { sx += px; sy += py; n++ }
                }
                if (n > 0) { cx = sx / n + 0.0; cy = sy / n + 0.0 }
            }
            balls.add(classify(f, cx, cy, r))
        }
        return BallDetection(balls, r)
    }

    private fun classify(f: Frame, cx: Double, cy: Double, r: Double): Ball {
        var white = 0; var dark = 0; var colored = 0; var n = 0
        var ringWhite = 0; var ringN = 0
        val ri = (r * 0.85).toInt() + 1
        for (dy in -ri..ri) for (dx in -ri..ri) {
            val px = (cx + dx).toInt(); val py = (cy + dy).toInt()
            if (px < 0 || px >= f.width || py < 0 || py >= f.height) continue
            val d = hypot(px - cx, py - cy)
            if (d > r * 0.8) continue
            val p = f.hsv[py * f.width + px]
            val s = Hsv.s(p); val v = Hsv.v(p)
            n++
            val isWhite = s <= 70 && v >= 185
            if (isWhite) white++
            if (v <= 70) dark++
            if (s >= 110 && v >= 70) colored++
            if (d >= r * 0.5) { ringN++; if (isWhite) ringWhite++ }
        }
        if (n == 0) return Ball(cx, cy, r, BallKind.UNKNOWN, 0.0)
        val wf = white.toDouble() / n
        val cf = colored.toDouble() / n
        val df = dark.toDouble() / n
        val rf = if (ringN > 0) ringWhite.toDouble() / ringN else 0.0
        val kind = when {
            wf >= 0.6 && cf <= 0.12 && rf >= 0.55 -> BallKind.CUE
            df >= 0.5 -> BallKind.EIGHT
            rf >= 0.25 && cf >= 0.15 -> BallKind.STRIPE
            else -> BallKind.SOLID
        }
        return Ball(cx, cy, r, kind, wf)
    }
}
