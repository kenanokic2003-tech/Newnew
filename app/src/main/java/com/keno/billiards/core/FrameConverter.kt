package com.keno.billiards.core

import java.nio.ByteBuffer

object FrameConverter {
    /** Analysis scale for a given screen size: keeps the analysed frame around 900 px on its long side. */
    fun scaleFor(srcW: Int, srcH: Int): Int = maxOf(1, Math.round(maxOf(srcW, srcH) / 900.0f))

    /**
     * Convert an RGBA_8888 buffer (as delivered by ImageReader) into an ARGB [argb] array of size
     * (srcW/k) x (srcH/k), averaging each k x k block (a box filter keeps thin lines visible).
     */
    fun fromRgba(buf: ByteBuffer, srcW: Int, srcH: Int, rowStride: Int, pixelStride: Int, k: Int, argb: IntArray) {
        val w = srcW / k
        val h = srcH / k
        val n = k * k
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0
                for (dy in 0 until k) {
                    var o = (y * k + dy) * rowStride + x * k * pixelStride
                    for (dx in 0 until k) {
                        r += buf.get(o).toInt() and 255
                        g += buf.get(o + 1).toInt() and 255
                        b += buf.get(o + 2).toInt() and 255
                        o += pixelStride
                    }
                }
                argb[y * w + x] = (255 shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }
}
