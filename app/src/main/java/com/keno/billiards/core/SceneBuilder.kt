package com.keno.billiards.core

import kotlin.math.max
import kotlin.math.min

/** Turns an [AnalysisResult] + [KenoSettings] into drawing primitives. */
object SceneBuilder {
    const val AIM = 0xFF00E5FF.toInt()
    const val TARGET = 0xFFFFD600.toInt()
    const val CUE = 0xFFFF80AB.toInt()
    const val OTHER = 0x99FFFFFF.toInt()
    const val OK = 0xFF69F0AE.toInt()
    const val BAD = 0xFFFF5252.toInt()
    const val TEXT = 0xFFFFFFFF.toInt()

    /** Gap (analysis px) between the end of the game's own guide line and where KENO's line starts. */
    const val PAD = 6.0

    /** Extra pixels around everything KENO draws that the analyser treats as unknown. Must be shared with the mask builder. */
    const val MASK_MARGIN = 1.5

    private fun withAlpha(c: Int, a: Int) = (a shl 24) or (c and 0xFFFFFF)

    fun build(res: AnalysisResult?, s: KenoSettings): Scene {
        if (res == null || !s.analysisOn) return Scene.EMPTY
        val prims = ArrayList<Prim>()
        val tb = res.table
        val det = res.det

        if (s.showDebug) debug(res, prims)

        val pred = res.prediction
        val trk = res.tracker
        if (pred != null && tb != null && det != null && trk != null && trk.state != AimState.HIDDEN) {
            val stale = trk.state == AimState.STALE
            val a = if (stale) 0x66 else 0xFF
            val r = det.radius
            val cue = pred.cue.points
            val contact = pred.contact

            // index of the cue path vertex where first contact happens
            var kContact = -1
            if (contact != null) {
                var bd = Double.MAX_VALUE
                for (i in cue.indices) {
                    val d = cue[i].dist(contact.cueAtContact)
                    if (d < bd) { bd = d; kContact = i }
                }
            }
            val preEnd = if (kContact >= 0) kContact else (if (cue.size > 1) 1 else 0)

            // ---- aim line: the game already draws its own white line + ring; KENO never draws over it
            //      (drawing over it made the analyser lose the line). KENO only adds its own ring when
            //      its contact point clearly disagrees with where the game's line ends. ----
            if (s.showAimLine && cue.size > 1 && contact != null) {
                val from = cue[0]
                val vis = res.aim?.visibleLength ?: 0.0
                val dir = (contact.cueAtContact - from).norm()
                val gameEnd = from + dir * vis
                if (vis <= 0.0 || gameEnd.dist(contact.cueAtContact) > 1.6 * r) {
                    val start = max(1.2 * r, vis + PAD)
                    if (start < from.dist(contact.cueAtContact))
                        prims.add(PLine(listOf(from + dir * start, contact.cueAtContact), withAlpha(AIM, a), 3.0))
                    prims.add(PCircle(contact.cueAtContact, r, withAlpha(AIM, a), 2.4))
                }
            }

            // ---- target ball ----
            val tgt = pred.target
            if (s.showTargetPath && tgt != null && contact != null) {
                val pts = tgt.points
                val upto = if (s.extendAim) pts.size else min(pts.size, 2)
                var tgtFull = false
                if (upto >= 2) {
                    val limited = if (s.extendAim) cutAfterBounces(pts.subList(0, upto), 2) else limitLength(pts.subList(0, upto), 8 * r)
                    prims.add(PLine(limited.map { it }, withAlpha(TARGET, a), 3.0))
                    tgtFull = limited.size == pts.size
                }
                if (s.showFinalPositions && s.extendAim && tgtFull) endMarker(prims, tgt, r, TARGET, a, "")
            }

            // ---- cue ball after contact / after cushions ----
            var cueFull = false
            if (s.showCuePath) {
                val post = if (kContact >= 0) cue.subList(kContact, cue.size)
                           else if (s.extendAim && cue.size > 2) cue.subList(1, cue.size) else emptyList()
                if (post.size >= 2) {
                    val pts = if (s.extendAim) cutAfterBounces(post, 1) else limitLength(post.subList(0, 2), 6 * r)
                    prims.add(PLine(pts, withAlpha(CUE, a), 2.8, true))
                    cueFull = pts.size == post.size
                }
                if (s.showFinalPositions && s.extendAim && cueFull) endMarker(prims, pred.cue, r, CUE, a, "BIJELA")
            }

            // ---- honest spin status next to the cue ball ----
            val cb = det.cue
            if (cb != null) {
                when {
                    s.spinMode == SpinMode.AUTO && !res.spin.usable ->
                        prims.add(PText(Vec2(cb.x + r * 1.4, cb.y - r * 1.4), "bez spina: spin se ne čita (${res.spin.label})", BAD, 11.0))
                    res.spin.usable && (Math.abs(res.spin.x) > 0.05 || Math.abs(res.spin.y) > 0.05) ->
                        prims.add(PText(Vec2(cb.x + r * 1.4, cb.y - r * 1.4), "spin %+.2f/%+.2f".format(res.spin.x, res.spin.y), AIM, 11.0))
                }
            }
        }
        return Scene(prims)
    }

    private fun endMarker(prims: MutableList<Prim>, p: BallPath, r: Double, color: Int, a: Int, label: String) {
        if (p.points.size < 2) return
        val e = p.end
        if (p.potted) {
            prims.add(PCircle(e, r * 0.9, withAlpha(OK, a), 2.0, false))
            val txt = if (label == "BIJELA") "BIJELA U RUPI!" else "RUPA"
            prims.add(PText(Vec2(e.x + r, e.y - r), txt, if (label == "BIJELA") BAD else OK, 12.0))
        } else if (p.truncated) {
            // prediction stopped after too many cushions: not a rest position, so no ring, just a small tick
            prims.add(PCircle(e, max(1.5, r * 0.15), withAlpha(color, a), 1.0, true))
        } else {
            prims.add(PCircle(e, r, withAlpha(color, a), 1.6))
        }
    }

    /** Keeps the path up to its [n]-th cushion hit (a turn of more than 25 degrees). */
    private fun cutAfterBounces(pts: List<Vec2>, n: Int): List<Vec2> {
        if (pts.size < 3) return pts
        var bounces = 0
        for (i in 1 until pts.size - 1) {
            val a = pts[i] - pts[i - 1]; val b = pts[i + 1] - pts[i]
            if (a.dot(a) < 4.0 || b.dot(b) < 4.0) continue
            val cosv = (a.x * b.x + a.y * b.y) / (Math.sqrt(a.dot(a)) * Math.sqrt(b.dot(b)))
            if (cosv < 0.906) { // > 25 degrees
                bounces++
                if (bounces >= n) return pts.subList(0, i + 1) // stop at the n-th cushion hit
            }
        }
        return pts
    }

    private fun limitLength(pts: List<Vec2>, maxLen: Double): List<Vec2> {
        val out = ArrayList<Vec2>()
        var acc = 0.0
        out.add(pts[0])
        for (i in 1 until pts.size) {
            val seg = pts[i].dist(pts[i - 1])
            if (acc + seg >= maxLen) {
                val t = (maxLen - acc) / seg
                out.add(pts[i - 1] + (pts[i] - pts[i - 1]) * t)
                return out
            }
            acc += seg
            out.add(pts[i])
        }
        return out
    }

    private fun debug(res: AnalysisResult, prims: MutableList<Prim>) {
        val tb = res.table
        if (tb != null) {
            val rc = tb.rect
            prims.add(PLine(listOf(Vec2(rc.left, rc.top), Vec2(rc.right, rc.top), Vec2(rc.right, rc.bottom),
                Vec2(rc.left, rc.bottom), Vec2(rc.left, rc.top)), 0xFFFFEB3B.toInt(), 1.0))
            for (p in tb.pockets) prims.add(PCircle(Vec2(p.x, p.y), 7.0, if (p.confirmed) OK else BAD, 1.4))
        }
        val det = res.det
        if (det != null) for (b in det.balls) {
            val col = when (b.kind) {
                BallKind.CUE -> 0xFFFFFFFF.toInt()
                BallKind.EIGHT -> 0xFFE040FB.toInt()
                BallKind.STRIPE -> 0xFF40C4FF.toInt()
                else -> OK
            }
            prims.add(PCircle(b.pos, b.r, col, 1.2))
        }
        val a = res.aim
        val cue = det?.cue
        if (a != null && cue != null) {
            val d = a.dir
            prims.add(PLine(listOf(cue.pos + d * (1.1 * cue.r), cue.pos + d * a.visibleLength), BAD, 1.0))
        }
        res.spin.widget?.let { prims.add(PCircle(Vec2(it.cx, it.cy), it.r, 0xFFFF9800.toInt(), 1.4)) }
        prims.add(PText(Vec2(8.0, 14.0), res.status + "  [${res.processingMs} ms]", TEXT, 10.0))
    }
}
