package com.keno.billiards.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class Pocket(val x: Double, val y: Double, val corner: Boolean, val confirmed: Boolean)

/**
 * Detected playing surface. [rect] is the felt (cushion-to-cushion) rectangle in frame pixels.
 * Pocket positions are derived from that rectangle (4 corners + 2 middle points) and each one
 * is verified by looking for the dark pocket hole in the frame ([Pocket.confirmed]).
 */
class TableModel(
    val rect: RectD,
    val feltHue: Int,
    val feltSat: Int,
    val feltVal: Int,
    val feltMinChannel: Int,
    val pockets: List<Pocket>,
    val confidence: Double,
) {
    val aspect get() = rect.width / rect.height
    val confirmedPockets get() = pockets.count { it.confirmed }

    fun isFelt(p: Int): Boolean {
        val s = Hsv.s(p)
        val v = Hsv.v(p)
        return Hsv.hueDist(Hsv.h(p), feltHue) <= FELT_HUE_TOL && s >= feltSat / 2 && v >= feltVal * 45 / 100
    }

    companion object {
        const val FELT_HUE_TOL = 17
    }
}

object TableDetector {

    fun detect(f: Frame): TableModel? {
        val w = f.width
        val h = f.height
        val hsv = f.hsv

        // 1. Dominant saturated hue = cloth colour.
        val hist = DoubleArray(36)
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = hsv[y * w + x]
                if (Hsv.s(p) >= 90 && Hsv.v(p) >= 70) hist[Hsv.h(p) / 10] += 1.0
                x += 2
            }
            y += 2
        }
        var bestBin = 0
        var bestScore = -1.0
        for (b in 0 until 36) {
            val sc = hist[(b + 35) % 36] + hist[b] + hist[(b + 1) % 36]
            if (sc > bestScore) { bestScore = sc; bestBin = b }
        }
        if (bestScore < w * h / 4.0 * 0.04) return null // not enough saturated colour on screen

        // 2. Refine hue, saturation and value of the cloth from the pixels near that hue.
        val centreHue = bestBin * 10 + 5
        var sx = 0.0
        var sy = 0.0
        val sHist = IntArray(256)
        val vHist = IntArray(256)
        val mHist = IntArray(256)
        var n = 0
        y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val i = y * w + x
                val p = hsv[i]
                if (Hsv.s(p) >= 90 && Hsv.v(p) >= 70 && Hsv.hueDist(Hsv.h(p), centreHue) <= 20) {
                    val a = Math.toRadians(Hsv.h(p).toDouble())
                    sx += cos(a); sy += sin(a)
                    sHist[Hsv.s(p)]++
                    vHist[Hsv.v(p)]++
                    mHist[Hsv.minChannel(f.argb[i])]++
                    n++
                }
                x += 2
            }
            y += 2
        }
        if (n < 200) return null
        var feltHue = Math.toDegrees(atan2(sy, sx)).roundToInt()
        if (feltHue < 0) feltHue += 360
        feltHue %= 360
        val feltSat = median(sHist, n)
        val feltVal = median(vHist, n)
        val feltMin = median(mHist, n)

        // provisional model (no pockets yet) so we can reuse isFelt()
        val probe = TableModel(RectD(0.0, 0.0, 1.0, 1.0), feltHue, feltSat, feltVal, feltMin, emptyList(), 0.0)

        // 3. Felt mask projections -> coarse bounding box.
        val rowCnt = IntArray(h)
        for (yy in 0 until h) {
            var c = 0
            val o = yy * w
            for (xx in 0 until w) if (probe.isFelt(hsv[o + xx])) c++
            rowCnt[yy] = c
        }
        val maxRow = rowCnt.maxOrNull() ?: 0
        if (maxRow < w * 0.25) return null
        val (y0, y1) = longestRun(rowCnt, h, maxRow * 0.3, 3) ?: return null
        val colCnt = IntArray(w)
        for (yy in y0..y1) {
            val o = yy * w
            for (xx in 0 until w) if (probe.isFelt(hsv[o + xx])) colCnt[xx]++
        }
        val maxCol = colCnt.maxOrNull() ?: 0
        val (x0, x1) = longestRun(colCnt, w, maxCol * 0.3, 3) ?: return null
        if (x1 - x0 < w * 0.3 || y1 - y0 < h * 0.25) return null

        // 4. Precise edges: median of per-row / per-column first felt run.
        val bandY0 = y0 + (0.2 * (y1 - y0)).toInt()
        val bandY1 = y1 - (0.2 * (y1 - y0)).toInt()
        val lefts = ArrayList<Int>()
        val rights = ArrayList<Int>()
        val stepY = max(1, (bandY1 - bandY0) / 50)
        var yy = bandY0
        while (yy <= bandY1) {
            val l = edgeScan(probe, hsv, yy * w, 1, w, x0 - 30, x1 + 30)
            val r = edgeScan(probe, hsv, yy * w, -1, w, x0 - 30, x1 + 30)
            if (l >= 0) lefts.add(l)
            if (r >= 0) rights.add(r)
            yy += stepY
        }
        if (lefts.size < 5 || rights.size < 5) return null
        val left = medianOf(lefts).toDouble()
        val right = medianOf(rights).toDouble() + 1.0

        val midX = (left + right) / 2
        val halfW = right - left
        val tops = ArrayList<Int>()
        val bottoms = ArrayList<Int>()
        val stepX = max(1, (halfW * 0.6 / 50).toInt())
        var xx = (left + halfW * 0.2).toInt()
        val xEnd = (right - halfW * 0.2).toInt()
        while (xx <= xEnd) {
            if (abs(xx - midX) > halfW * 0.12) {
                val t = edgeScanV(probe, hsv, xx, 1, w, h, y0 - 30, y1 + 30)
                val b = edgeScanV(probe, hsv, xx, -1, w, h, y0 - 30, y1 + 30)
                if (t >= 0) tops.add(t)
                if (b >= 0) bottoms.add(b)
            }
            xx += stepX
        }
        if (tops.size < 5 || bottoms.size < 5) return null
        val top = medianOf(tops).toDouble()
        val bottom = medianOf(bottoms).toDouble() + 1.0
        // 4b. The cushion strip is often the same hue as the cloth but a little darker. Step inwards->outwards
        //     from just inside the edge and stop where brightness drops: that is the cushion nose, which
        //     is what balls actually bounce from.
        fun vAt(x: Int, y: Int): Int =
            if (x < 0 || y < 0 || x >= w || y >= h) 0 else Hsv.v(hsv[y * w + x])
        fun cAt(x: Int, y: Int): IntArray {
            if (x < 0 || y < 0 || x >= w || y >= h) return intArrayOf(0, 0, 0)
            val p = f.argb[y * w + x]
            return intArrayOf((p shr 16) and 255, (p shr 8) and 255, p and 255)
        }
        // Colour-step search: works for cushion bands that are lighter OR darker than the felt.
        fun refine(coarse: Double, inward: Int, samples: List<Int>, horizontal: Boolean): Double {
            val maxK = (0.09 * (bottom - top)).toInt().coerceAtLeast(12)
            val found = ArrayList<Int>()
            for (smp in samples) {
                val st = DoubleArray(maxK + 1)
                for (k in 4..maxK) {
                    var d = 0.0
                    for (c in 0..2) {
                        var a = 0.0; var b2 = 0.0
                        for (i in 0..2) {
                            val pa = (coarse + inward * (k + i)).toInt()
                            val pb = (coarse + inward * (k - 1 - i)).toInt()
                            a += (if (horizontal) cAt(pa, smp) else cAt(smp, pa))[c]
                            b2 += (if (horizontal) cAt(pb, smp) else cAt(smp, pb))[c]
                        }
                        d += Math.abs(a - b2) / 3.0
                    }
                    st[k] = d
                }
                val mx = st.max()
                if (mx < 24.0) { found.add(coarse.toInt()); continue }
                var kk = 4
                for (k in 4..maxK) if (st[k] >= 0.6 * mx) kk = k
                found.add((coarse + inward * kk).toInt())
            }
            return if (found.isEmpty()) coarse else medianOf(found).toDouble()
        }
        val rowSamples = ArrayList<Int>().also { var q = bandY0; while (q <= bandY1) { it.add(q); q += stepY } }
        val colSamples = ArrayList<Int>().also {
            var q = (left + halfW * 0.2).toInt()
            while (q <= xEnd) { if (abs(q - midX) > halfW * 0.12) it.add(q); q += stepX }
        }
        val left2 = refine(left, 1, rowSamples, true)
        val right2 = refine(right - 1, -1, rowSamples, true) + 1.0
        val top2 = refine(top, 1, colSamples, false)
        val bottom2 = refine(bottom - 1, -1, colSamples, false) + 1.0
        val maxShift = 0.12 * (bottom - top)
        val rect = RectD(
            if (abs(left2 - left) <= maxShift) left2 else left,
            if (abs(top2 - top) <= maxShift) top2 else top,
            if (abs(right2 - right) <= maxShift) right2 else right,
            if (abs(bottom2 - bottom) <= maxShift) bottom2 else bottom,
        )
        val aspect = rect.width / rect.height
        if (aspect < 1.4 || aspect > 2.8) return null

        // 5. Pockets: corners + middle of the long sides, verified by dark holes.
        val rApprox = rect.width / 32.0
        val spots = listOf(
            Triple(rect.left, rect.top, Pair(-1.0, -1.0)),
            Triple(rect.right, rect.top, Pair(1.0, -1.0)),
            Triple(rect.left, rect.bottom, Pair(-1.0, 1.0)),
            Triple(rect.right, rect.bottom, Pair(1.0, 1.0)),
            Triple(rect.cx, rect.top, Pair(0.0, -1.0)),
            Triple(rect.cx, rect.bottom, Pair(0.0, 1.0)),
        )
        val pockets = ArrayList<Pocket>()
        for ((i, s) in spots.withIndex()) {
            val dirLen = sqrt(s.third.first * s.third.first + s.third.second * s.third.second)
            val ox = s.third.first / dirLen
            val oy = s.third.second / dirLen
            val cx = s.first + ox * 0.5 * rApprox
            val cy = s.second + oy * 0.5 * rApprox
            val rr = 1.3 * rApprox
            var dark = 0
            var tot = 0
            val ix0 = max(0, (cx - rr).toInt()); val ix1 = min(w - 1, (cx + rr).toInt())
            val iy0 = max(0, (cy - rr).toInt()); val iy1 = min(h - 1, (cy + rr).toInt())
            for (py in iy0..iy1) for (px in ix0..ix1) {
                val dx = px - cx; val dy = py - cy
                if (dx * dx + dy * dy <= rr * rr) {
                    tot++
                    if (Hsv.v(hsv[py * w + px]) < 70) dark++
                }
            }
            val confirmed = tot > 0 && dark.toDouble() / tot >= 0.22
            pockets.add(Pocket(s.first, s.second, i < 4, confirmed))
        }
        val confirmedCount = pockets.count { it.confirmed }
        val aspectScore = 1.0 - min(1.0, abs(aspect - 2.0) / 0.5)
        val conf = 0.5 * (confirmedCount / 6.0) + 0.5 * aspectScore
        return TableModel(rect, feltHue, feltSat, feltVal, feltMin, pockets, conf)
    }

    private fun median(hist: IntArray, n: Int): Int {
        var acc = 0
        for (i in hist.indices) {
            acc += hist[i]
            if (acc * 2 >= n) return i
        }
        return 0
    }

    private fun medianOf(l: List<Int>): Int = l.sorted()[l.size / 2]

    /** Longest run of indices with value >= thr, tolerating gaps of up to [gap] indices. */
    private fun longestRun(v: IntArray, n: Int, thr: Double, gap: Int): Pair<Int, Int>? {
        var bestA = -1; var bestB = -1
        var a = -1; var last = -1
        for (i in 0 until n) {
            if (v[i] >= thr) {
                if (a < 0) a = i
                last = i
            } else if (a >= 0 && i - last > gap) {
                if (last - a > bestB - bestA) { bestA = a; bestB = last }
                a = -1
            }
        }
        if (a >= 0 && last - a > bestB - bestA) { bestA = a; bestB = last }
        return if (bestA < 0) null else Pair(bestA, bestB)
    }

    /** Scan horizontally from outside; first x where >=5 of next 6 pixels are felt. */
    private fun edgeScan(t: TableModel, hsv: IntArray, rowOff: Int, dir: Int, w: Int, from: Int, to: Int): Int {
        val a = max(2, from)
        val b = min(w - 3, to)
        if (dir > 0) {
            var x = a
            while (x <= b - 6) {
                var c = 0
                for (k in 0 until 6) if (t.isFelt(hsv[rowOff + x + k])) c++
                if (c >= 5) return x
                x++
            }
        } else {
            var x = b
            while (x >= a + 6) {
                var c = 0
                for (k in 0 until 6) if (t.isFelt(hsv[rowOff + x - k])) c++
                if (c >= 5) return x
                x--
            }
        }
        return -1
    }

    private fun edgeScanV(t: TableModel, hsv: IntArray, x: Int, dir: Int, w: Int, h: Int, from: Int, to: Int): Int {
        val a = max(2, from)
        val b = min(h - 3, to)
        if (dir > 0) {
            var y = a
            while (y <= b - 6) {
                var c = 0
                for (k in 0 until 6) if (t.isFelt(hsv[(y + k) * w + x])) c++
                if (c >= 5) return y
                y++
            }
        } else {
            var y = b
            while (y >= a + 6) {
                var c = 0
                for (k in 0 until 6) if (t.isFelt(hsv[(y - k) * w + x])) c++
                if (c >= 5) return y
                y--
            }
        }
        return -1
    }
}
