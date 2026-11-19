package com.keno.billiards.core

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Plain 2D vector. Screen coordinates unless stated otherwise (x right, y DOWN). */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(k: Double) = Vec2(x * k, y * k)
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun len() = hypot(x, y)
    fun dist(o: Vec2) = hypot(x - o.x, y - o.y)
    fun norm(): Vec2 {
        val l = len()
        return if (l < 1e-12) Vec2(0.0, 0.0) else Vec2(x / l, y / l)
    }
}

data class RectD(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2.0
    val cy get() = (top + bottom) / 2.0
    fun contains(x: Double, y: Double) = x >= left && x <= right && y >= top && y <= bottom
}

object Angles {
    /** Smallest signed difference a-b in radians, in (-PI, PI]. */
    fun diff(a: Double, b: Double): Double {
        var d = a - b
        while (d > PI) d -= 2 * PI
        while (d <= -PI) d += 2 * PI
        return d
    }

    fun deg(rad: Double) = rad * 180.0 / PI
    fun rad(deg: Double) = deg * PI / 180.0
    fun of(v: Vec2) = atan2(v.y, v.x)
}

/** Packed HSV helpers: (h 0..359) << 16 | (s 0..255) << 8 | v 0..255. */
object Hsv {
    fun pack(argb: Int): Int {
        val r = (argb shr 16) and 255
        val g = (argb shr 8) and 255
        val b = argb and 255
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val s = if (mx == 0) 0 else d * 255 / mx
        var h = 0
        if (d != 0) {
            h = when (mx) {
                r -> 60 * (g - b) / d
                g -> 60 * (b - r) / d + 120
                else -> 60 * (r - g) / d + 240
            }
            h = ((h % 360) + 360) % 360
        }
        return (h shl 16) or (s shl 8) or mx
    }

    fun h(p: Int) = p ushr 16
    fun s(p: Int) = (p shr 8) and 255
    fun v(p: Int) = p and 255

    fun hueDist(a: Int, b: Int): Int {
        val d = Math.abs(a - b)
        return if (d > 180) 360 - d else d
    }

    fun minChannel(argb: Int): Int =
        min((argb shr 16) and 255, min((argb shr 8) and 255, argb and 255))
}

/**
 * One analysis frame: ARGB pixels at reduced resolution, plus a packed HSV array.
 * [scale] is the integer factor between full-resolution screen pixels and frame pixels.
 */
class Frame(val width: Int, val height: Int, val argb: IntArray, val scale: Int = 1) {
    val hsv = IntArray(width * height)

    /** Call once after argb is final (after inpainting). */
    fun computeHsv() {
        val n = width * height
        for (i in 0 until n) hsv[i] = Hsv.pack(argb[i])
    }
}

/**
 * KENO draws on top of the game and MediaProjection captures those drawings too.
 * Pixels covered by KENO's own overlay are therefore "unknown": we replace each one
 * with the colour of the nearest pixel KENO did NOT draw on, so that KENO's own lines
 * and markers can never be mistaken for table, balls or the game's aim line.
 */
object Inpaint {
    fun fill(frame: Frame, mask: BooleanArray) {
        val w = frame.width
        val h = frame.height
        val n = w * h
        var any = false
        for (i in 0 until n) if (mask[i]) { any = true; break }
        if (!any) return
        val INF = Int.MAX_VALUE / 2
        val dist = IntArray(n)
        val src = IntArray(n)
        for (i in 0 until n) {
            if (mask[i]) { dist[i] = INF; src[i] = -1 } else { dist[i] = 0; src[i] = i }
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (dist[i] == 0) continue
            if (x > 0) relax(dist, src, i, i - 1, 3)
            if (y > 0) {
                relax(dist, src, i, i - w, 3)
                if (x > 0) relax(dist, src, i, i - w - 1, 4)
                if (x < w - 1) relax(dist, src, i, i - w + 1, 4)
            }
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (dist[i] == 0) continue
            if (x < w - 1) relax(dist, src, i, i + 1, 3)
            if (y < h - 1) {
                relax(dist, src, i, i + w, 3)
                if (x < w - 1) relax(dist, src, i, i + w + 1, 4)
                if (x > 0) relax(dist, src, i, i + w - 1, 4)
            }
        }
        for (i in 0 until n) if (mask[i] && src[i] >= 0) frame.argb[i] = frame.argb[src[i]]
    }

    private fun relax(dist: IntArray, src: IntArray, i: Int, nb: Int, c: Int) {
        val d = dist[nb] + c
        if (src[nb] >= 0 && d < dist[i]) { dist[i] = d; src[i] = src[nb] }
    }
}

/** Exact squared Euclidean distance transform (Felzenszwalb & Huttenlocher). */
object Edt {
    /**
     * For every pixel set in [fg], the distance (px) to the nearest pixel that is NOT set.
     * Pixels outside the array count as background.
     */
    fun distance(fg: BooleanArray, w: Int, h: Int): FloatArray {
        val INF = 1e9f
        val f = FloatArray(w * h) { if (fg[it]) INF else 0f }
        val len = max(w, h)
        val d = FloatArray(len)
        val v = IntArray(len)
        val z = FloatArray(len + 1)
        // columns
        for (x in 0 until w) {
            for (y in 0 until h) d[y] = f[y * w + x]
            dt1d(d, h, v, z)
            for (y in 0 until h) f[y * w + x] = dtOut[y]
        }
        for (y in 0 until h) {
            for (x in 0 until w) d[x] = f[y * w + x]
            dt1d(d, w, v, z)
            for (x in 0 until w) f[y * w + x] = dtOut[x]
        }
        for (i in f.indices) f[i] = if (fg[i]) sqrt(f[i].toDouble()).toFloat() else 0f
        return f
    }

    private var dtOut = FloatArray(0)

    private fun dt1d(d: FloatArray, n: Int, v: IntArray, z: FloatArray) {
        if (dtOut.size < n) dtOut = FloatArray(n)
        var k = 0
        v[0] = 0
        z[0] = -1e12f
        z[1] = 1e12f
        for (q in 1 until n) {
            var s: Float
            while (true) {
                val p = v[k]
                s = ((d[q] + q.toFloat() * q) - (d[p] + p.toFloat() * p)) / (2f * q - 2f * p)
                if (s <= z[k] && k > 0) k-- else break
            }
            if (s <= z[k]) {
                // k == 0 and s <= z[0] (cannot happen with -1e12) - keep structure safe
                v[k] = q
                z[k + 1] = 1e12f
            } else {
                k++
                v[k] = q
                z[k] = s
                z[k + 1] = 1e12f
            }
        }
        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            dtOut[q] = (q - p).toFloat() * (q - p) + d[p]
        }
    }
}
