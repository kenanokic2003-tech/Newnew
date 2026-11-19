package com.keno.billiards.core

import kotlin.math.hypot

class SpinInfo(
    /** Short human readable source/status, e.g. "sa ekrana", "ručno", "NIJE PROČITAN". */
    val label: String,
    val x: Double,
    val y: Double,
    /** true only when [x],[y] are a real measurement or an explicit manual value. */
    val usable: Boolean,
    val widget: SpinWidgetPx?,
)

class AnalysisResult(
    val width: Int,
    val height: Int,
    val table: TableModel?,
    val det: BallDetection?,
    val aim: AimLine?,
    val spin: SpinInfo,
    val tracker: TrackerOutput?,
    val prediction: ShotPrediction?,
    val status: String,
    val processingMs: Long,
)

/**
 * One analysis pass over a captured frame:
 *  inpaint KENO's own overlay -> table -> pockets -> balls -> cue ball -> aim line -> spin ->
 *  shot prediction -> aim stabiliser.
 * Not thread-safe: use from a single analysis thread.
 */
class KenoAnalyzer {
    private val tracker = AimTracker()
    private var table: TableModel? = null
    private var frameNo = 0
    private var tableMisses = 0

    private var autoWidget: SpinWidgetPx? = null
    private var lastAutoTry = Long.MIN_VALUE / 2
    @Volatile var autoLocateRequested = false
    /** Set when an auto-locate attempt finishes; the service reads and clears it. */
    @Volatile var autoLocateOutcome: SpinWidgetPx? = null
    @Volatile var autoLocateDone = false

    private var cachedPred: ShotPrediction? = null
    private var cachedDet: BallDetection? = null
    private var cachedAngle = 0.0
    private var cachedKey = ""

    fun analyze(frame: Frame, mask: BooleanArray?, nowMs: Long, s: KenoSettings): AnalysisResult {
        val t0 = System.nanoTime()
        if (mask != null) Inpaint.fill(frame, mask)
        frame.computeHsv()
        frameNo++
        val w = frame.width
        val h = frame.height

        // ---- table ----
        if (table == null || frameNo % 8 == 0) {
            val t = TableDetector.detect(frame)
            if (t != null) { table = t; tableMisses = 0 } else if (++tableMisses > 40) table = null
        }
        val tb = table
        if (tb == null) {
            tracker.reset()
            return done(frame, null, null, null, SpinInfo("-", 0.0, 0.0, false, null), null, null,
                "Stol nije pronađen (otvori 8 Ball Pool u landscape modu)", t0)
        }

        // ---- balls ----
        val rawDet = BallDetector.detect(frame, tb)
        val det = if (rawDet == null) null else stabilize(rawDet, mask, w, h)
        if (det == null || det.balls.isEmpty()) {
            return done(frame, tb, det, null, SpinInfo("-", 0.0, 0.0, false, null), null, null,
                "Stol OK (${tb.confirmedPockets}/6 džepova), kugle nisu pronađene", t0)
        }
        val cue = det.cue
        val spin = resolveSpin(frame, s, nowMs, tb)

        if (cue == null) {
            return done(frame, tb, det, null, spin, null, null,
                "Stol OK, ${det.balls.size} kugli, bijela kugla nije pronađena", t0)
        }

        // ---- aim line (game's own guide line) ----
        val aim = AimDetector.detect(frame, tb, cue)
        val out = tracker.update(nowMs, aim?.angle, det.balls.map { it.pos }, det.radius, s)

        // ---- prediction ----
        var pred: ShotPrediction? = null
        val angle = out.angle
        if (angle != null && out.state != AimState.HIDDEN) {
            val key = "${s.power}|${s.spinStrength}|${s.cushionAdjustPx}|${s.maxCueCushions}|${spin.x}|${spin.y}"
            val reuse = cachedPred != null && cachedKey == key && Math.abs(Angles.diff(angle, cachedAngle)) < 1e-9 &&
                sameScene(cachedDet, det)
            if (reuse) pred = cachedPred
            else {
                pred = ShotPredictor.predict(tb, det, angle, spin.x, spin.y, s)
                cachedPred = pred; cachedDet = det; cachedAngle = angle; cachedKey = key
            }
        }

        val status = buildString {
            append("Stol OK (${tb.confirmedPockets}/6 džepova) • kugli: ${det.balls.size} • bijela: da • ")
            append(if (aim == null) "ciljna linija igre: NIJE VIDLJIVA" else "cilj: %.1f°".format(Angles.deg(aim.angle)))
            append(" • spin: ${spin.label}")
            if (spin.usable) append(" (%+.2f, %+.2f)".format(spin.x, spin.y))
            when (out.state) {
                AimState.HIDDEN -> if (out.ballsMoving) append(" • kugle se kreću") else if (aim != null) append(" • smiruje se…")
                AimState.STALE -> append(" • osvježavam…")
                else -> {}
            }
        }
        return done(frame, tb, det, aim, spin, out, pred, status, t0)
    }

    private fun done(
        f: Frame, tb: TableModel?, det: BallDetection?, aim: AimLine?, spin: SpinInfo,
        out: TrackerOutput?, pred: ShotPrediction?, status: String, t0: Long,
    ) = AnalysisResult(f.width, f.height, tb, det, aim, spin, out, pred, status, (System.nanoTime() - t0) / 1_000_000)

    private var lastStable: List<Ball> = emptyList()

    /** True if KENO's own overlay covers a noticeable part of this ball (so its detection is less reliable). */
    private fun covered(b: Ball, mask: BooleanArray?, w: Int, h: Int): Boolean {
        if (mask == null) return false
        var n = 0; var m = 0
        val ri = b.r.toInt() + 1
        for (dy in -ri..ri) for (dx in -ri..ri) {
            if (dx * dx + dy * dy > b.r * b.r) continue
            val x = b.x.toInt() + dx; val y = b.y.toInt() + dy
            if (x < 0 || y < 0 || x >= w || y >= h) continue
            n++
            if (mask[y * w + x]) m++
        }
        return n > 0 && m.toDouble() / n >= 0.03
    }

    /**
     * Balls under KENO's own drawing are detected from inpainted pixels and can jump or vanish for a frame.
     * Without this, KENO would see its own overlay as "balls moving", hide itself, and flicker forever.
     * A covered ball keeps its last reliable position if the new detection is close to it (or missing).
     */
    private fun stabilize(det: BallDetection, mask: BooleanArray?, w: Int, h: Int): BallDetection {
        if (mask == null || lastStable.isEmpty()) { lastStable = det.balls; return det }
        val r = det.radius
        val out = ArrayList<Ball>()
        val used = BooleanArray(lastStable.size)
        for (b in det.balls) {
            var bi = -1; var bd = Double.MAX_VALUE
            for ((i, q) in lastStable.withIndex()) {
                val d = hypot(b.x - q.x, b.y - q.y)
                if (d < bd) { bd = d; bi = i }
            }
            if (bi >= 0 && bd < 0.7 * r && covered(b, mask, w, h)) {
                val q = lastStable[bi]
                out.add(Ball(q.x, q.y, q.r, q.kind, q.whiteFrac)); used[bi] = true
            } else {
                out.add(b)
                if (bi >= 0 && bd < 0.7 * r) used[bi] = true
            }
        }
        // a covered ball that was not detected this frame is carried over instead of "disappearing"
        for ((i, q) in lastStable.withIndex()) if (!used[i] && covered(q, mask, w, h)) {
            var near = false
            for (b in det.balls) if (hypot(b.x - q.x, b.y - q.y) < 1.2 * r) { near = true; break }
            if (!near) out.add(q)
        }
        lastStable = out
        return BallDetection(out, det.radius)
    }

    private fun sameScene(a: BallDetection?, b: BallDetection): Boolean {
        if (a == null || a.balls.size != b.balls.size) return false
        val tol = 0.2 * b.radius
        for (x in b.balls) {
            var best = Double.MAX_VALUE
            for (y in a.balls) best = minOf(best, hypot(x.x - y.x, x.y - y.y))
            if (best > tol) return false
        }
        return true
    }

    private fun resolveSpin(f: Frame, s: KenoSettings, nowMs: Long, tb: TableModel): SpinInfo {
        when (s.spinMode) {
            SpinMode.OFF -> return SpinInfo("isključen", 0.0, 0.0, true, null)
            SpinMode.MANUAL -> return SpinInfo("ručno", s.manualSpinX, s.manualSpinY, true, null)
            SpinMode.AUTO -> {}
        }
        var wdg: SpinWidgetPx? = s.spinWidget?.toPx(f.width, f.height)
        val userCalibrated = wdg != null
        if (!userCalibrated) {
            if (autoLocateRequested || (autoWidget == null && nowMs - lastAutoTry > 2500)) {
                lastAutoTry = nowMs
                val found = SpinReader.autoLocate(f, tb)
                if (found != null) autoWidget = found
                if (autoLocateRequested) {
                    autoLocateOutcome = found
                    autoLocateDone = true
                    autoLocateRequested = false
                }
            }
            wdg = autoWidget
        }
        if (wdg == null) return SpinInfo("spin lopta nije nađena", 0.0, 0.0, false, null)
        val rd = SpinReader.read(f, wdg)
        if (!rd.found) return SpinInfo("crvena tačka nije vidljiva", 0.0, 0.0, false, wdg)
        return SpinInfo(if (userCalibrated) "sa ekrana (kalibrisano)" else "sa ekrana (auto)", rd.x, rd.y, true, wdg)
    }
}
