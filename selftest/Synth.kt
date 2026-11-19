package selftest

import com.keno.billiards.core.Vec2
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

/** Ground truth + renderer for a fake landscape 8 Ball Pool screen (full resolution, 1640x720 like a Redmi 14C). */
class SynthBall(val x: Double, val y: Double, val kind: String /* cue, solid, stripe, eight */, val color: Color)

class SynthScene(
    val w: Int = 1640,
    val h: Int = 720,
    val feltLeft: Double = 300.0, val feltRight: Double = 1300.0,
    val feltTop: Double = 130.0, val feltBottom: Double = 630.0,
    val cloth: Color = Color(22, 150, 180),
    val cushionBand: Double = 18.0,
    val cushionDarkness: Double = 0.7,
    val ballR: Double = 16.0,
    val balls: List<SynthBall> = emptyList(),
    val aimAngle: Double? = null,         // screen radians
    val guideLength: Double = 400.0,       // visible length of the game's guide line from the cue centre
    val guideWidth: Float = 3f,
    val guideRing: Boolean = true,
    val stick: Boolean = true,
    val stickWhite: Boolean = false,
    val spinDot: Vec2? = null,             // -1..1, x right, y up
    val spinCx: Double = 140.0, val spinCy: Double = 560.0, val spinR: Double = 34.0,
    val noise: Int = 3,
) {
    val cue get() = balls.first { it.kind == "cue" }

    fun render(seed: Long = 1): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(14, 18, 26); g.fillRect(0, 0, w, h)
        // wooden rail
        g.color = Color(120, 74, 38)
        g.fillRoundRect((feltLeft - 70).toInt(), (feltTop - 70).toInt(), (feltRight - feltLeft + 140).toInt(), (feltBottom - feltTop + 140).toInt(), 40, 40)
        // cushion band (same hue, darker)
        val band = Color((cloth.red * cushionDarkness).toInt(), (cloth.green * cushionDarkness).toInt(), (cloth.blue * cushionDarkness).toInt())
        g.color = band
        g.fillRect((feltLeft - cushionBand).toInt(), (feltTop - cushionBand).toInt(),
            (feltRight - feltLeft + 2 * cushionBand).toInt(), (feltBottom - feltTop + 2 * cushionBand).toInt())
        // felt with soft vignette
        val cx = (feltLeft + feltRight) / 2; val cy = (feltTop + feltBottom) / 2
        val rg = RadialGradientPaint(cx.toFloat(), cy.toFloat(), ((feltRight - feltLeft) * 0.62).toFloat(),
            floatArrayOf(0f, 1f), arrayOf(cloth, Color((cloth.red * 0.85).toInt(), (cloth.green * 0.85).toInt(), (cloth.blue * 0.85).toInt())))
        g.paint = rg
        g.fillRect(feltLeft.toInt(), feltTop.toInt(), (feltRight - feltLeft).toInt(), (feltBottom - feltTop).toInt())
        // rail diamonds (white dots on the wood)
        g.color = Color(240, 230, 200)
        for (i in 1..7) { val x = feltLeft + (feltRight - feltLeft) * i / 8.0; g.fill(Ellipse2D.Double(x - 4, feltTop - 46.0, 8.0, 8.0)); g.fill(Ellipse2D.Double(x - 4, feltBottom + 38.0, 8.0, 8.0)) }
        // pockets
        g.color = Color(5, 5, 5)
        for ((px, py, rr) in listOf(
            Triple(feltLeft - 6, feltTop - 6, 27.0), Triple(feltRight + 6, feltTop - 6, 27.0),
            Triple(feltLeft - 6, feltBottom + 6, 27.0), Triple(feltRight + 6, feltBottom + 6, 27.0),
            Triple(cx, feltTop - 16, 23.0), Triple(cx, feltBottom + 16, 23.0))) g.fill(Ellipse2D.Double(px - rr, py - rr, 2 * rr, 2 * rr))

        // shadows then balls
        for (b in balls) {
            g.color = Color(0, 0, 0, 70)
            g.fill(Ellipse2D.Double(b.x - ballR + 2, b.y - ballR + 3, 2 * ballR, 2 * ballR))
        }
        // cue stick (behind the cue ball, opposite the aim)
        if (stick && aimAngle != null) {
            val c = cue
            val dx = cos(aimAngle); val dy = sin(aimAngle)
            val nx = -dy; val ny = dx
            val a0 = ballR + 22; val a1 = a0 + 430
            val p = Path2D.Double()
            p.moveTo(c.x - dx * a0 + nx * 3, c.y - dy * a0 + ny * 3)
            p.lineTo(c.x - dx * a1 + nx * 9, c.y - dy * a1 + ny * 9)
            p.lineTo(c.x - dx * a1 - nx * 9, c.y - dy * a1 - ny * 9)
            p.lineTo(c.x - dx * a0 - nx * 3, c.y - dy * a0 - ny * 3)
            p.closePath()
            g.color = if (stickWhite) Color(245, 245, 245) else Color(205, 160, 100)
            g.fill(p)
        }
        for (b in balls) drawBall(g, b)

        // the game's own thin white guide line + ghost ring
        if (aimAngle != null) {
            val c = cue
            val dx = cos(aimAngle); val dy = sin(aimAngle)
            g.color = Color(255, 255, 255)
            g.stroke = BasicStroke(guideWidth, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            g.draw(java.awt.geom.Line2D.Double(c.x + dx * (ballR + 2), c.y + dy * (ballR + 2), c.x + dx * guideLength, c.y + dy * guideLength))
            if (guideRing) {
                val gx = c.x + dx * guideLength; val gy = c.y + dy * guideLength
                g.draw(Ellipse2D.Double(gx - ballR, gy - ballR, 2 * ballR, 2 * ballR))
            }
        }
        // spin widget
        if (spinDot != null) {
            g.color = Color(60, 60, 60); g.fill(Ellipse2D.Double(spinCx - spinR - 4, spinCy - spinR - 4, 2 * spinR + 8, 2 * spinR + 8))
            g.color = Color(250, 250, 250); g.fill(Ellipse2D.Double(spinCx - spinR, spinCy - spinR, 2 * spinR, 2 * spinR))
            g.color = Color(215, 25, 30)
            val dx = spinCx + spinDot.x * spinR * 0.85; val dy = spinCy - spinDot.y * spinR * 0.85
            g.fill(Ellipse2D.Double(dx - 6, dy - 6, 12.0, 12.0))
        }
        g.dispose()
        if (noise > 0) {
            val rnd = java.util.Random(seed)
            for (y in 0 until h) for (x in 0 until w) {
                val p = img.getRGB(x, y)
                val n = rnd.nextInt(2 * noise + 1) - noise
                fun cl(v: Int) = v.coerceIn(0, 255)
                img.setRGB(x, y, (255 shl 24) or (cl(((p shr 16) and 255) + n) shl 16) or (cl(((p shr 8) and 255) + n) shl 8) or cl((p and 255) + n))
            }
        }
        return img
    }

    private fun drawBall(g: java.awt.Graphics2D, b: SynthBall) {
        val r = ballR
        val base = when (b.kind) {
            "cue" -> Color(250, 250, 250)
            "eight" -> Color(20, 20, 20)
            "stripe" -> Color(250, 250, 250)
            else -> b.color
        }
        val rg = RadialGradientPaint((b.x - r * 0.3).toFloat(), (b.y - r * 0.3).toFloat(), (r * 1.5).toFloat(),
            floatArrayOf(0f, 1f), arrayOf(base, Color((base.red * 0.75).toInt(), (base.green * 0.75).toInt(), (base.blue * 0.75).toInt())))
        g.paint = rg
        val circle = Ellipse2D.Double(b.x - r, b.y - r, 2 * r, 2 * r)
        g.fill(circle)
        if (b.kind == "stripe") {
            val old = g.clip
            g.clip = circle
            g.color = b.color
            g.fillRect((b.x - r).toInt(), (b.y - r * 0.55).toInt(), (2 * r).toInt(), (r * 1.1).toInt())
            g.clip = old
        }
        if (b.kind != "cue") {
            g.color = Color(255, 255, 255)
            g.fill(Ellipse2D.Double(b.x - r * 0.42, b.y - r * 0.42, r * 0.84, r * 0.84))
            g.color = Color(30, 30, 30)
            g.fill(Ellipse2D.Double(b.x - r * 0.14, b.y - r * 0.14, r * 0.28, r * 0.28))
        }
    }

    /** RGBA byte buffer as MediaProjection's ImageReader would deliver it (with a padded row stride). */
    fun toRgbaBuffer(img: BufferedImage): Triple<ByteBuffer, Int, Int> {
        val pixelStride = 4
        val rowStride = w * 4 + 32
        val buf = ByteBuffer.allocate(rowStride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = img.getRGB(x, y)
            val o = y * rowStride + x * 4
            buf.put(o, ((p shr 16) and 255).toByte()); buf.put(o + 1, ((p shr 8) and 255).toByte())
            buf.put(o + 2, (p and 255).toByte()); buf.put(o + 3, 255.toByte())
        }
        return Triple(buf, rowStride, pixelStride)
    }
}
