package com.keno.billiards.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The aim direction as read from the game's own guide line.
 * [angle] is in screen coordinates (atan2(dy, dx), y down).
 * [visibleLength] is how far from the cue ball centre the game's line is visible (frame px).
 */
class AimLine(val angle: Double, val visibleLength: Double, val confidence: Double) {
    val dir get() = Vec2(cos(angle), sin(angle))
}

/**
 * KENO cannot know where you aim unless the game shows it, so it reads the thin bright guide line
 * 8 Ball Pool draws from the cue ball. It scans rays around the cue ball, keeps the one with the
 * longest continuous bright run, checks that the run is THIN (so the cue stick, which is thick,
 * is rejected), and fits the exact angle with a least-squares line through the cue ball centre.
 */
object AimDetector {

    fun detect(f: Frame, table: TableModel, cue: Ball): AimLine? {
        val rect = table.rect
        val r = cue.r
        val thr = max(table.feltMinChannel + 55, 130)
        val maxD = min(rect.width * 0.9, hypot(rect.width, rect.height))
        val startD = 1.15 * r

        fun lineLike(x: Double, y: Double): Boolean {
            val px = x.roundToInt(); val py = y.roundToInt()
            if (px < 0 || py < 0 || px >= f.width || py >= f.height) return false
            return Hsv.minChannel(f.argb[py * f.width + px]) >= thr
        }

        class Run(val len: Double, val end: Double)

        fun run(angle: Double): Run {
            val dx = cos(angle); val dy = sin(angle)
            var d = startD
            var lastHit = -1.0
            var firstHit = -1.0
            var gap = 0
            while (d < maxD) {
                val x = cue.x + dx * d
                val y = cue.y + dy * d
                if (!rect.contains(x, y)) break
                if (lineLike(x, y)) {
                    if (firstHit < 0) firstHit = d
                    lastHit = d
                    gap = 0
                } else {
                    gap++
                    if (firstHit >= 0 && gap > 3) break
                    if (firstHit < 0 && d - startD > 0.6 * r) break // line must start at the ball
                }
                d += 1.0
            }
            return if (firstHit < 0) Run(0.0, 0.0) else Run(lastHit - firstHit, lastHit)
        }

        // coarse sweep, 1 degree
        val coarse = ArrayList<Pair<Double, Run>>()
        for (i in 0 until 360) {
            val a = Angles.rad(i.toDouble())
            coarse.add(Pair(a, run(a)))
        }
        coarse.sortByDescending { it.second.len }
        val candidates = ArrayList<Pair<Double, Run>>()
        for (c in coarse) {
            if (c.second.len < 2.2 * r) break
            if (candidates.none { abs(Angles.diff(it.first, c.first)) < Angles.rad(8.0) }) candidates.add(c)
            if (candidates.size >= 3) break
        }
        if (candidates.isEmpty()) return null

        var best: AimLine? = null
        var bestScore = 0.0
        for (c in candidates) {
            // fine sweep around the candidate
            var fa = c.first
            var fr = c.second
            var k = -1.5
            while (k <= 1.5) {
                val a = c.first + Angles.rad(k)
                val rr = run(a)
                if (rr.len > fr.len + 0.5) { fr = rr; fa = a }
                k += 0.1
            }
            // least squares: line through the cue centre, principal axis of the line pixels
            var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var n = 0
            val dx = cos(fa); val dy = sin(fa)
            var d = startD
            while (d <= fr.end) {
                // look in a thin band across the line
                for (o in -2..2) {
                    val x = cue.x + dx * d - dy * o
                    val y = cue.y + dy * d + dx * o
                    if (lineLike(x, y)) {
                        val rx = x - cue.x; val ry = y - cue.y
                        sxx += rx * rx; syy += ry * ry; sxy += rx * ry; n++
                    }
                }
                d += 1.0
            }
            if (n < 8) continue
            var ang = 0.5 * atan2(2 * sxy, sxx - syy)
            // principal axis is ambiguous by 180 degrees: pick the one closest to the scanned ray
            if (abs(Angles.diff(ang, fa)) > PI / 2) ang += PI
            if (abs(Angles.diff(ang, fa)) > Angles.rad(3.0)) ang = fa

            // thickness test: samples 4 px either side of the ray must not be line-like (the cue stick is thick)
            val nx = -sin(ang); val ny = cos(ang)
            var thin = 0; var total = 0
            d = startD
            while (d <= fr.end) {
                val x = cue.x + cos(ang) * d
                val y = cue.y + sin(ang) * d
                if (lineLike(x, y) || lineLike(cue.x + cos(ang) * d + nx, cue.y + sin(ang) * d + ny)) {
                    total++
                    val a = lineLike(x + nx * 4.5, y + ny * 4.5)
                    val b = lineLike(x - nx * 4.5, y - ny * 4.5)
                    if (!a && !b) thin++
                }
                d += 2.0
            }
            val thinFrac = if (total == 0) 0.0 else thin.toDouble() / total
            val score = fr.len * (if (thinFrac >= 0.7) 1.0 else 0.15)
            if (score > bestScore) {
                bestScore = score
                val conf = min(1.0, fr.len / (8.0 * r)) * thinFrac
                best = AimLine(ang, fr.end, conf)
            }
        }
        val b = best ?: return null
        return if (bestScore >= 2.2 * r) b else null
    }
}
