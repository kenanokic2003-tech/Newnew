package com.keno.billiards.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Drawing primitives in analysis-frame pixels. The Android view scales them to the screen. */
sealed class Prim

class PLine(val pts: List<Vec2>, val color: Int, val width: Double, val dashed: Boolean = false) : Prim()
class PCircle(val c: Vec2, val r: Double, val color: Int, val width: Double, val filled: Boolean = false) : Prim()
class PText(val at: Vec2, val text: String, val color: Int, val size: Double) : Prim()

class Scene(val prims: List<Prim>) {

    /**
     * Mark every pixel KENO draws on (plus [margin] px) so the analyser can treat it as unknown.
     * [extraRects] are regions covered by KENO's own windows (floating button / panel): l,t,r,b.
     */
    fun rasterMask(mask: BooleanArray, w: Int, h: Int, margin: Double, extraRects: List<IntArray>) {
        java.util.Arrays.fill(mask, false)
        for (r in extraRects) {
            val x0 = max(0, r[0]); val y0 = max(0, r[1]); val x1 = min(w - 1, r[2]); val y1 = min(h - 1, r[3])
            for (y in y0..y1) for (x in x0..x1) mask[y * w + x] = true
        }
        for (p in prims) when (p) {
            is PLine -> {
                for (i in 1 until p.pts.size) segment(mask, w, h, p.pts[i - 1], p.pts[i], p.width / 2 + margin)
            }
            is PCircle -> {
                if (p.filled) disc(mask, w, h, p.c.x, p.c.y, p.r + margin)
                else {
                    val steps = max(16, (2 * PI * p.r / 0.8).toInt())
                    for (k in 0 until steps) {
                        val a = 2 * PI * k / steps
                        disc(mask, w, h, p.c.x + cos(a) * p.r, p.c.y + sin(a) * p.r, p.width / 2 + margin)
                    }
                }
            }
            is PText -> {
                val tw = p.text.length * 0.62 * p.size
                val x0 = max(0, (p.at.x - margin).toInt()); val x1 = min(w - 1, (p.at.x + tw + margin).toInt())
                val y0 = max(0, (p.at.y - p.size - margin).toInt()); val y1 = min(h - 1, (p.at.y + 0.35 * p.size + margin).toInt())
                for (y in y0..y1) for (x in x0..x1) mask[y * w + x] = true
            }
        }
    }

    private fun segment(mask: BooleanArray, w: Int, h: Int, a: Vec2, b: Vec2, rad: Double) {
        val len = hypot(b.x - a.x, b.y - a.y)
        val steps = max(1, (len / 0.7).toInt())
        for (k in 0..steps) {
            val t = k.toDouble() / steps
            disc(mask, w, h, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, rad)
        }
    }

    private fun disc(mask: BooleanArray, w: Int, h: Int, cx: Double, cy: Double, rad: Double) {
        val x0 = max(0, (cx - rad).toInt()); val x1 = min(w - 1, (cx + rad).toInt() + 1)
        val y0 = max(0, (cy - rad).toInt()); val y1 = min(h - 1, (cy + rad).toInt() + 1)
        val r2 = rad * rad
        for (y in y0..y1) {
            val dy = y - cy
            for (x in x0..x1) {
                val dx = x - cx
                if (dx * dx + dy * dy <= r2) mask[y * w + x] = true
            }
        }
    }

    companion object {
        val EMPTY = Scene(emptyList())
    }
}
