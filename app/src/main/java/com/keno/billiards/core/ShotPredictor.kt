package com.keno.billiards.core

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class ContactPx(
    val cueAtContact: Vec2,
    val targetCenter: Vec2,
    val contactPoint: Vec2,
    val targetIndex: Int,
)

/** One ball's predicted polyline (frame px) and where it ends up. */
class BallPath(val ballIndex: Int, val points: List<Vec2>, val potted: Boolean, val pocketIndex: Int, val truncated: Boolean = false) {
    val end get() = points.last()
}

class ShotPrediction(
    val aimAngle: Double,
    val spinX: Double,
    val spinY: Double,
    val cue: BallPath,
    val contact: ContactPx?,
    val target: BallPath?,
    val others: List<BallPath>,
    val cushionsBeforeContact: Int,
)

object ShotPredictor {

    /**
     * @param spinX +right (relative to the shot direction), spinY +up (top spin / follow), each -1..1
     */
    fun predict(
        table: TableModel,
        det: BallDetection,
        aimAngle: Double,
        spinX: Double,
        spinY: Double,
        s: KenoSettings,
    ): ShotPrediction? {
        val cueIdx = det.cueIndex
        if (cueIdx < 0) return null
        val Rm = 0.02855
        val rPx = det.radius
        val scale = Rm / rPx // metres per frame pixel
        val adj = s.cushionAdjustPx
        val rect = table.rect
        val left = rect.left + adj; val right = rect.right - adj
        val topPx = rect.top + adj; val botPx = rect.bottom - adj

        fun toWorld(p: Vec2) = Vec2((p.x - left) * scale, (botPx - p.y) * scale)
        fun toPx(p: Vec2) = Vec2(p.x / scale + left, botPx - p.y / scale)

        val pockets = ArrayList<Vec2>()
        val corner = ArrayList<Boolean>()
        for (pk in table.pockets) {
            pockets.add(toWorld(Vec2(pk.x + (if (pk.corner) (if (pk.x < rect.cx) adj else -adj) else 0.0),
                pk.y + (if (pk.y < rect.cy) adj else -adj))))
            corner.add(pk.corner)
        }
        val simTable = SimTable(0.0, (right - left) * scale, 0.0, (botPx - topPx) * scale, pockets, corner)

        val params = PhysicsParams(ballRadius = Rm, maxCueCushions = s.maxCueCushions)
        val sim = ArrayList<SimBall>()
        for (b in det.balls) {
            val w = toWorld(b.pos)
            sim.add(SimBall(w.x, w.y))
        }
        // aim direction: screen (y down) -> world (y up)
        val dWorld = Vec2(cos(aimAngle), -sin(aimAngle))
        val v0 = 0.6 + s.power.coerceIn(0.0, 1.0) * 5.4 // m/s: soft tap .. hard shot
        val off = 0.5 * Rm * s.spinStrength
        Physics.strike(sim[cueIdx], dWorld, v0, spinX.coerceIn(-1.0, 1.0) * off, spinY.coerceIn(-1.0, 1.0) * off, params)

        val res = Physics.simulate(sim, cueIdx, simTable, params)

        fun pathOf(i: Int): BallPath {
            val b = sim[i]
            return BallPath(i, b.path.map { toPx(it) }, b.potted, b.pocketIndex, b.truncated)
        }
        val cue = pathOf(cueIdx)
        val fc = res.firstContact
        var contact: ContactPx? = null
        var target: BallPath? = null
        if (fc != null) {
            val cueC = toPx(fc.cuePos)
            val tgtC = toPx(fc.targetPos)
            val n = (tgtC - cueC).norm()
            contact = ContactPx(cueC, tgtC, tgtC - n * rPx, fc.targetIndex)
            target = pathOf(fc.targetIndex)
        }
        val others = ArrayList<BallPath>()
        for (i in sim.indices) {
            if (i == cueIdx || i == fc?.targetIndex) continue
            if (sim[i].path.size > 1) others.add(pathOf(i))
        }
        return ShotPrediction(aimAngle, spinX, spinY, cue, contact, target, others, res.cueCushionsBeforeContact)
    }

    /** Distance helper for tests. */
    fun length(pts: List<Vec2>): Double {
        var l = 0.0
        for (i in 1 until pts.size) l += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        return l
    }
}
