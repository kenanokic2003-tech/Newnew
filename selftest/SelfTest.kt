package selftest

import com.keno.billiards.core.*
import java.awt.Color
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

var failures = 0
var checks = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    checks++
    if (!ok) failures++
    println((if (ok) "  PASS  " else "  FAIL  ") + name + (if (detail.isNotEmpty()) "   [$detail]" else ""))
}

val outDir = File(System.getProperty("selftest.out") ?: "selftest_out").also { it.mkdirs() }

fun frameOf(sc: SynthScene, seed: Long = 1): Pair<Frame, java.awt.image.BufferedImage> {
    val img = sc.render(seed)
    val (buf, rs, ps) = sc.toRgbaBuffer(img)
    val k = FrameConverter.scaleFor(sc.w, sc.h)
    val argb = IntArray((sc.w / k) * (sc.h / k))
    FrameConverter.fromRgba(buf, sc.w, sc.h, rs, ps, k, argb)
    return Pair(Frame(sc.w / k, sc.h / k, argb, k), img)
}

val ballColors = listOf(Color(240, 200, 20), Color(30, 60, 200), Color(210, 30, 30), Color(110, 40, 150),
    Color(240, 120, 20), Color(20, 130, 60), Color(120, 20, 30))

fun randomBalls(seed: Long, n: Int, sc: SynthScene, withCue: Boolean = true): List<SynthBall> {
    val rnd = java.util.Random(seed)
    val out = ArrayList<SynthBall>()
    val r = sc.ballR
    fun free(x: Double, y: Double) = out.none { hypot(it.x - x, it.y - y) < 3.2 * r }
    var tries = 0
    while (out.size < n + (if (withCue) 1 else 0) && tries++ < 5000) {
        val x = sc.feltLeft + 2 * r + rnd.nextDouble() * (sc.feltRight - sc.feltLeft - 4 * r)
        val y = sc.feltTop + 2 * r + rnd.nextDouble() * (sc.feltBottom - sc.feltTop - 4 * r)
        if (!free(x, y)) continue
        val idx = out.size
        val kind = if (withCue && idx == 0) "cue" else when (idx % 4) { 0 -> "stripe"; 1 -> "solid"; 2 -> "solid"; else -> if (idx == 7) "eight" else "stripe" }
        out.add(SynthBall(x, y, kind, ballColors[idx % ballColors.size]))
    }
    return out
}

fun main() {
    println("=== KENO core self-test ===")
    testTable()
    testBalls()
    testRack()
    testAim()
    testSpinRead()
    testOverlayFeedback()
    testPhysics()
    testTracker()
    testEndToEnd()
    println("\n$checks checks, $failures failed")
    if (failures > 0) System.exit(1)
}

// ---------------------------------------------------------------------------------------------
fun testTable() {
    println("\n[table + pockets]")
    val cloths = listOf("blue" to Color(22, 150, 180), "green" to Color(25, 130, 60), "red" to Color(170, 40, 50), "purple" to Color(110, 60, 160))
    for ((nm, c) in cloths) {
        for (dark in listOf(0.7, 1.0)) {
            val sc = SynthScene(cloth = c, cushionDarkness = dark, balls = emptyList())
            val (f, _) = frameOf(sc)
            f.computeHsv()
            val t = TableDetector.detect(f)
            if (t == null) { check("$nm cloth, cushion x$dark: detected", false); continue }
            val k = f.scale.toDouble()
            val el = abs(t.rect.left * k - sc.feltLeft); val er = abs(t.rect.right * k - sc.feltRight)
            val et = abs(t.rect.top * k - sc.feltTop); val eb = abs(t.rect.bottom * k - sc.feltBottom)
            val tol = if (dark < 1.0) 6.0 else 26.0 // a cushion strip with the SAME brightness as the cloth cannot be separated; user adjusts with the cushion slider
            val worst = maxOf(el, er, et, eb)
            check("$nm cloth, cushion x$dark: rect within ${tol.toInt()}px (full-res)", worst <= tol, "L%.1f R%.1f T%.1f B%.1f".format(el, er, et, eb))
            check("$nm cloth, cushion x$dark: 6/6 pockets confirmed", t.confirmedPockets == 6, "${t.confirmedPockets}/6")
        }
    }
}

fun checkBalls(label: String, sc: SynthScene, f: Frame, det: BallDetection?, tolPx: Double = 1.2) {
    if (det == null) { check("$label: detection ran", false); return }
    val k = f.scale.toDouble()
    check("$label: ball count ${sc.balls.size}", det.balls.size == sc.balls.size, "found ${det.balls.size}")
    var worst = 0.0
    var matched = 0
    for (gt in sc.balls) {
        var best = Double.MAX_VALUE
        for (b in det.balls) best = minOf(best, hypot(b.x * k - gt.x, b.y * k - gt.y) / k)
        if (best < 4.0) matched++
        worst = maxOf(worst, best)
    }
    check("$label: all balls matched, worst centre error <= $tolPx analysis px", matched == sc.balls.size && worst <= tolPx, "matched $matched, worst %.2f px".format(worst))
    check("$label: radius estimate", abs(det.radius * k - sc.ballR) <= 2.0, "est %.1f gt %.1f (full-res)".format(det.radius * k, sc.ballR))
    val cue = det.cue
    val gtc = sc.balls.firstOrNull { it.kind == "cue" }
    if (gtc != null) check("$label: white cue ball identified at the right place", cue != null && hypot(cue.x * k - gtc.x, cue.y * k - gtc.y) < 3.0)
}

fun testBalls() {
    println("\n[ball detection]")
    for (seed in 1L..4L) {
        val base = SynthScene()
        val sc = SynthScene(balls = randomBalls(seed, 8, base), aimAngle = null, stick = false)
        val (f, img) = frameOf(sc, seed)
        f.computeHsv()
        val t = TableDetector.detect(f)!!
        checkBalls("random layout $seed", sc, f, BallDetector.detect(f, t))
        if (seed == 1L) ImageIO.write(img, "png", File(outDir, "synth_random1.png"))
    }
    // balls next to cushions, with guide line + stick present
    val base = SynthScene()
    val rnd = listOf(
        SynthBall(300.0 + 17, 400.0, "cue", Color.WHITE),
        SynthBall(700.0, 130.0 + 17, "solid", ballColors[1]),
        SynthBall(1000.0, 630.0 - 17, "stripe", ballColors[2]),
        SynthBall(1300.0 - 17, 300.0, "solid", ballColors[3]),
    )
    val sc = SynthScene(balls = rnd, aimAngle = 0.1, guideLength = 300.0)
    val (f, _) = frameOf(sc)
    f.computeHsv()
    checkBalls("balls touching cushions + aim line + stick", sc, f, BallDetector.detect(f, TableDetector.detect(f)!!), 1.6)
}

fun testRack() {
    println("\n[rack of 15 + cue]")
    val r = 16.0
    val balls = ArrayList<SynthBall>()
    val apexX = 960.0; val apexY = 380.0
    var i = 0
    for (row in 0 until 5) for (j in 0..row) {
        val x = apexX + row * r * Math.sqrt(3.0) * 1.0
        val y = apexY + (j - row / 2.0) * 2 * r
        balls.add(SynthBall(x, y, if (i == 4) "eight" else if (i % 2 == 0) "solid" else "stripe", ballColors[i % ballColors.size]))
        i++
    }
    balls.add(0, SynthBall(520.0, 380.0, "cue", Color.WHITE))
    val sc = SynthScene(balls = balls)
    val (f, img) = frameOf(sc)
    f.computeHsv()
    val t = TableDetector.detect(f)!!
    val det = BallDetector.detect(f, t)
    checkBalls("rack", sc, f, det, 1.5)
    ImageIO.write(img, "png", File(outDir, "synth_rack.png"))
}

fun testAim() {
    println("\n[aim line detection]")
    val rnd = java.util.Random(7)
    var worst = 0.0
    var fails = 0
    val n = 24
    for (i in 0 until n) {
        val base = SynthScene()
        val balls = randomBalls(100L + i, 6, base)
        val cue = balls.first { it.kind == "cue" }
        val ang = rnd.nextDouble() * 2 * Math.PI - Math.PI
        val len = 120.0 + rnd.nextDouble() * 400
        val sc = SynthScene(balls = balls, aimAngle = ang, guideLength = len, stickWhite = (i % 6 == 5), guideWidth = if (i % 3 == 0) 2f else 3f)
        val (f, _) = frameOf(sc, i.toLong())
        f.computeHsv()
        val t = TableDetector.detect(f)
        val det = if (t != null) BallDetector.detect(f, t) else null
        val c = det?.cue
        val aim = if (t != null && c != null) AimDetector.detect(f, t, c) else null
        if (aim == null) { fails++; 
            val blockers = balls.filter { it !== cue }.filter { b ->
                val dx = Math.cos(ang); val dy = Math.sin(ang)
                val t = (b.x - cue.x) * dx + (b.y - cue.y) * dy
                val dist = Math.abs((b.x - cue.x) * dy - (b.y - cue.y) * dx)
                t > 0 && t < len && dist < 2.0 * sc.ballR
            }
            println("    miss: angle %.1f len %.0f cue=(%.0f,%.0f) ballsOnLine=%d detectedCue=%s".format(Angles.deg(ang), len, cue.x, cue.y, blockers.size, c != null))
            continue }
        val err = abs(Angles.deg(Angles.diff(aim.angle, ang)))
        worst = maxOf(worst, err)
        if (err > 0.5) { fails++; println("    bad: angle %.2f err %.2f len %.0f whiteStick=%b".format(Angles.deg(ang), err, len, sc.stickWhite)) }
    }
    check("aim angle found within 0.5 deg in $n random shots (incl. white sticks)", fails == 0, "fails=$fails worst=%.2f deg".format(worst))

    // no guide line drawn -> must report none, not invent one
    val base = SynthScene()
    val sc = SynthScene(balls = randomBalls(5, 6, base), aimAngle = null)
    val (f, _) = frameOf(sc)
    f.computeHsv()
    val t = TableDetector.detect(f)!!
    val det = BallDetector.detect(f, t)!!
    check("no guide line on screen -> no aim reported", AimDetector.detect(f, t, det.cue!!) == null)
}

fun testSpinRead() {
    println("\n[spin widget]")
    val cases = listOf(Vec2(0.0, 0.0), Vec2(0.0, 0.8), Vec2(0.0, -0.8), Vec2(0.7, 0.0), Vec2(-0.7, 0.0), Vec2(0.5, 0.5), Vec2(-0.5, -0.5))
    var worst = 0.0
    var autoOk = 0
    for (c in cases) {
        val base = SynthScene()
        val sc = SynthScene(balls = randomBalls(3, 5, base), spinDot = c)
        val (f, _) = frameOf(sc)
        f.computeHsv()
        val k = f.scale
        val wd = SpinWidgetPx(sc.spinCx / k, sc.spinCy / k, sc.spinR / k)
        val rd = SpinReader.read(f, wd)
        if (!rd.found) { check("spin dot found for $c", false); continue }
        val err = hypot(rd.x - c.x * 0.85, rd.y - c.y * 0.85)
        worst = maxOf(worst, err)
        val auto = SpinReader.autoLocate(f, null)
        if (auto != null && hypot(auto.cx * k - sc.spinCx, auto.cy * k - sc.spinCy) < 4 && abs(auto.r * k - sc.spinR) < 4) autoOk++
    }
    check("calibrated spin reading within 0.12 of truth (dot drawn at 0.85 of radius)", worst < 0.12, "worst %.3f".format(worst))
    check("auto-locate finds the white-ball-with-red-dot widget", autoOk == cases.size, "$autoOk/${cases.size}")
    // no widget on screen -> must say not found
    val sc = SynthScene(balls = randomBalls(3, 5, SynthScene()), spinDot = null)
    val (f, _) = frameOf(sc)
    f.computeHsv()
    val k = f.scale
    val rd = SpinReader.read(f, SpinWidgetPx(sc.spinCx / k, sc.spinCy / k, sc.spinR / k))
    check("no widget -> reading reports found=false (no fake spin)", !rd.found)
    check("no widget -> auto-locate returns null", SpinReader.autoLocate(f, null) == null)
}

/** KENO paints on the screen it captures. The analysis must neither be disturbed by it nor lock onto it. */
fun testOverlayFeedback() {
    println("\n[self-capture: KENO's own overlay must not disturb detection]")
    val base = SynthScene()
    var balls = randomBalls(11, 7, base)
    val cue = balls.first { it.kind == "cue" }
    val ang = atan2(300.0 - cue.y, 900.0 - cue.x)
    val sc = SynthScene(balls = balls, aimAngle = ang, guideLength = 420.0, spinDot = Vec2(0.0, 0.6))
    val s = KenoSettings(spinMode = SpinMode.AUTO, stabilizeAim = false)
    val analyzer = KenoAnalyzer()
    val (f0, img) = frameOf(sc)
    val k = f0.scale
    var prevAngle: Double? = null
    var scene = Scene.EMPTY
    var worstDrift = 0.0
    var lastRes: AnalysisResult? = null
    for (iter in 0 until 8) {
        // capture = the clean game frame with KENO's previous scene composited on top
        val (f, _) = frameOf(sc, 1)
        val composited = f.argb.clone()
        compositeScene(composited, f.width, f.height, scene)
        val frame = Frame(f.width, f.height, composited, k)
        val mask = BooleanArray(f.width * f.height)
        scene.rasterMask(mask, f.width, f.height, SceneBuilder.MASK_MARGIN, emptyList())
        val res = analyzer.analyze(frame, mask, 1000L + iter * 150L, s)
        lastRes = res
        val a = res.aim?.angle
        if (a != null && prevAngle != null) worstDrift = maxOf(worstDrift, abs(Angles.deg(Angles.diff(a, prevAngle))))
        if (a != null) prevAngle = a
        println("    iter $iter: aim=${res.aim?.let { "%.2f".format(Angles.deg(it.angle)) }} balls=${res.det?.balls?.size} tracker=${res.tracker?.state} moving=${res.tracker?.ballsMoving} pred=${res.prediction != null} mask=${mask.count { it }}")
        scene = SceneBuilder.build(res, s)
    }
    check("aim angle stays on the game's real line over 8 frames with KENO's overlay drawn", prevAngle != null && abs(Angles.deg(Angles.diff(prevAngle, ang))) < 0.5 &&
        worstDrift < 0.5, "final err %.2f deg, worst frame-to-frame drift %.2f deg".format(abs(Angles.deg(Angles.diff(prevAngle ?: 0.0, ang))), worstDrift))
    check("ball count unchanged with overlay drawn", lastRes?.det?.balls?.size == balls.size, "found ${lastRes?.det?.balls?.size}")
    check("KENO overlay produced primitives", scene.prims.isNotEmpty())
    // render the final composite for a visual check
    val (f, _) = frameOf(sc, 1)
    val comp = f.argb.clone(); compositeScene(comp, f.width, f.height, scene)
    savePng(comp, f.width, f.height, "overlay_composited.png", 2)
}

fun compositeScene(argb: IntArray, w: Int, h: Int, scene: Scene) {
    val img = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    img.setRGB(0, 0, w, h, argb, 0, w)
    val g = img.createGraphics()
    g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
    for (p in scene.prims) when (p) {
        is PLine -> { g.color = Color(p.color, true); g.stroke = java.awt.BasicStroke(p.width.toFloat())
            for (i in 1 until p.pts.size) g.draw(java.awt.geom.Line2D.Double(p.pts[i - 1].x, p.pts[i - 1].y, p.pts[i].x, p.pts[i].y)) }
        is PCircle -> { g.color = Color(p.color, true); g.stroke = java.awt.BasicStroke(p.width.toFloat())
            val e = java.awt.geom.Ellipse2D.Double(p.c.x - p.r, p.c.y - p.r, 2 * p.r, 2 * p.r); if (p.filled) g.fill(e) else g.draw(e) }
        is PText -> { g.color = Color(p.color, true); g.font = java.awt.Font("SansSerif", java.awt.Font.BOLD, p.size.toInt()); g.drawString(p.text, p.at.x.toFloat(), p.at.y.toFloat()) }
    }
    g.dispose()
    img.getRGB(0, 0, w, h, argb, 0, w)
}

fun savePng(argb: IntArray, w: Int, h: Int, name: String, up: Int = 1) {
    val img = java.awt.image.BufferedImage(w * up, h * up, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until h * up) for (x in 0 until w * up) img.setRGB(x, y, argb[(y / up) * w + x / up])
    ImageIO.write(img, "png", File(outDir, name))
}

// ---------------------------------------------------------------------------------------------
fun simTable(): SimTable {
    val W = 2.54; val H = 1.27
    return SimTable(0.0, W, 0.0, H,
        listOf(Vec2(0.0, 0.0), Vec2(W, 0.0), Vec2(0.0, H), Vec2(W, H), Vec2(W / 2, 0.0), Vec2(W / 2, H)),
        listOf(true, true, true, true, false, false))
}

fun testPhysics() {
    println("\n[physics]")
    val p = PhysicsParams()
    val R = p.ballRadius
    val t = simTable()
    val v0 = 2.0

    fun headOn(topFrac: Double): Pair<SimBall, SimBall> {
        val cue = SimBall(1.2 - 2 * R - 0.12, 0.63); val tg = SimBall(1.2, 0.63)
        Physics.strike(cue, Vec2(1.0, 0.0), v0, 0.0, topFrac * R, p)
        val res = Physics.simulate(listOf(cue, tg), 0, t, p)
        return Pair(res.balls[0], res.balls[1])
    }
    val tLong = SimTable(0.0, 10.0, 0.0, 1.27, emptyList(), emptyList())
    fun headOnLong(topFrac: Double): Pair<SimBall, SimBall> {
        val cue = SimBall(1.2 - 2 * R - 0.12, 0.63); val tg = SimBall(1.2, 0.63)
        Physics.strike(cue, Vec2(1.0, 0.0), v0, 0.0, topFrac * R, p)
        val res = Physics.simulate(listOf(cue, tg), 0, tLong, p)
        return Pair(res.balls[0], res.balls[1])
    }
    val (cueStun, tgStun) = headOnLong(0.0)
    val (cueFollow, _) = headOnLong(0.4)
    val (cueDraw, _) = headOnLong(-0.4)
    val contactX = 1.2 - 2 * R
    println("    head-on: stun cue stops at x=%.3f (contact %.3f), follow ends x=%.3f, draw ends x=%.3f, target ends x=%.3f".format(cueStun.x, contactX, cueFollow.x, cueDraw.x, tgStun.x))
    check("stun (centre ball) head-on: cue stops almost dead at the contact point", abs(cueStun.x - contactX) < 0.12)
    check("top spin / follow: cue ball rolls FORWARD past the contact point", cueFollow.x > contactX + 0.25)
    check("back spin / draw: cue ball comes BACK", cueDraw.x < contactX - 0.12)
    check("follow goes further forward than stun, draw further back (ordering)", cueFollow.x > cueStun.x && cueStun.x > cueDraw.x)
    check("target ball is driven forward by all three", tgStun.x > 1.2 + 0.3)

    // 30-degree cut with a stun ball: cue and target leave at ~90 degrees (the classic tangent-line rule)
    run {
        val cut = Math.toRadians(30.0)
        val tg = SimBall(1.5, 0.63)
        // ghost-ball: cue centre is offset from the line of centres by 2R*sin(cut)
        val cx = 1.5 - 2 * R * cos(cut) - 0.12
        val cy = 0.63 + 2 * R * sin(cut)
        val cue = SimBall(cx, cy)
        Physics.strike(cue, Vec2(1.0, 0.0), 1.2, 0.0, 0.0, p)
        val res = Physics.simulate(listOf(cue, tg), 0, t, p)
        val cp = res.balls[0].path; val tp = res.balls[1].path
        val cueDir = atan2(cp[2].y - cp[1].y, cp[2].x - cp[1].x)
        val tgtDir = atan2(tp[1].y - tp[0].y, tp[1].x - tp[0].x) // simplified path: start -> next vertex
        val between = abs(Math.toDegrees(cueDir - tgtDir))
        println("    30° cut stun: target leaves at %.1f°, cue at %.1f°, angle between %.1f°".format(Math.toDegrees(tgtDir), Math.toDegrees(cueDir), between))
        check("target ball leaves along the line of centres (-30° vs cue line)", abs(Math.toDegrees(tgtDir) + 30.0) < 1.0)
        check("stun shot: cue and target part at about 90° (tangent line rule)", abs(between - 90.0) < 8.0, "%.1f°".format(between))
    }

    // Cushion: no spin 45° bank off the top cushion, then side spin changes the rebound.
    fun bank(side: Double, e: Double = 0.0): Double {
        val cue = SimBall(0.8, 0.4)
        val d = Vec2(cos(Math.toRadians(45.0)), sin(Math.toRadians(45.0)))
        Physics.strike(cue, d, 1.8, side * R, 0.0, p)
        val res = Physics.simulate(listOf(cue, SimBall(2.4, 0.2)), 0, t, PhysicsParams(maxCueCushions = 2))
        val path = res.balls[0].path
        // find vertex of the first cushion hit (top cushion y = H - R)
        val idx = path.indexOfFirst { Math.abs(it.y - (1.27 - R)) < 1e-3 }
        val hit = path[idx]
        var after = path[idx + 1]
        for (j in idx + 1 until path.size) { after = path[j]; if (hypot(after.x - hit.x, after.y - hit.y) > 0.3) break }
        return Math.toDegrees(atan2(after.y - hit.y, after.x - hit.x))
    }
    val none = bank(0.0)
    val rightEng = bank(+0.5)
    val leftEng = bank(-0.5)
    println("    45° bank off the top cushion: no spin leaves at %.1f°, right english %.1f°, left english %.1f°".format(none, rightEng, leftEng))
    check("no-spin bank reflects downward at roughly the mirror angle (-45°, softened by cushion loss)", none < -30.0 && none > -55.0, "%.1f°".format(none))
    check("side spin changes the cushion rebound angle", abs(rightEng - leftEng) > 3.0, "difference %.1f°".format(abs(rightEng - leftEng)))
    check("opposite side spins deflect in opposite directions about the no-spin line", (rightEng - none) * (leftEng - none) < 0)

    // Pocketing: a ball struck straight at a corner pocket goes in.
    run {
        val cue = SimBall(1.0, 0.63)
        val tg = SimBall(0.3, 0.3)
        // aim the ghost ball so that target heads to the (0,0) corner
        val toPocket = Vec2(-tg.x, -tg.y); val n = toPocket.norm()
        val ghost = Vec2(tg.x - n.x * 2 * R, tg.y - n.y * 2 * R)
        val d = Vec2(ghost.x - cue.x, ghost.y - cue.y).norm()
        Physics.strike(cue, d, 2.0, 0.0, 0.0, p)
        val res = Physics.simulate(listOf(cue, tg), 0, t, p)
        check("object ball pots in the corner pocket it was aimed at", res.balls[1].potted && res.balls[1].pocketIndex == 0)
    }
    // Energy sanity: nothing leaves the table
    run {
        val rnd = java.util.Random(3)
        var ok = true
        for (i in 0 until 20) {
            val cue = SimBall(0.5 + rnd.nextDouble(), 0.3 + rnd.nextDouble() * 0.6)
            val balls = arrayListOf(cue, SimBall(1.4, 0.5), SimBall(1.9, 0.9))
            val a = rnd.nextDouble() * 2 * Math.PI
            Physics.strike(cue, Vec2(cos(a), sin(a)), 4.0, (rnd.nextDouble() - 0.5) * R, (rnd.nextDouble() - 0.5) * R, p)
            val res = Physics.simulate(balls, 0, t, p)
            for (b in res.balls) if (!b.potted && (b.x < -0.01 || b.x > 2.55 || b.y < -0.01 || b.y > 1.28)) ok = false
        }
        check("random hard shots: no ball ever leaves the table", ok)
    }
    // Performance
    run {
        val balls = ArrayList<SimBall>()
        for (i in 0 until 16) balls.add(SimBall(0.9 + (i % 5) * 0.06, 0.5 + (i / 5) * 0.06))
        balls[0] = SimBall(0.4, 0.63)
        Physics.strike(balls[0], Vec2(1.0, 0.0), 6.0, 0.0, 0.0, p)
        val t0 = System.nanoTime()
        Physics.simulate(balls, 0, t, p)
        val ms = (System.nanoTime() - t0) / 1e6
        println("    full 16-ball break simulation: %.0f ms on this machine".format(ms))
        check("full break simulates in under 600 ms (desktop JVM; phone will be slower)", ms < 600)
    }
}

// ---------------------------------------------------------------------------------------------
fun testTracker() {
    println("\n[aim stabiliser: small nudge vs large move]")
    val s = KenoSettings(smallMoveDeg = 0.8)
    val tr = AimTracker()
    val balls = listOf(Vec2(100.0, 100.0), Vec2(200.0, 150.0))
    var t = 0L
    fun tick(deg: Double?, ms: Long = 100): TrackerOutput { t += ms; return tr.update(t, deg?.let { Angles.rad(it) }, balls, 9.0, s) }
    // initial: stays hidden until the aim is settled
    var o = tick(10.0); check("first sight: hidden while settling", o.state == AimState.HIDDEN)
    o = tick(10.0); o = tick(10.0); o = tick(10.0)
    check("after settling: visible at 10°", o.state == AimState.VISIBLE && abs(Angles.deg(o.angle!!) - 10.0) < 0.01)
    o = tick(10.5)
    check("tiny nudge (0.5°): stays visible", o.state == AimState.VISIBLE, "angle %.2f".format(Angles.deg(o.angle ?: 0.0)))
    o = tick(10.55)
    check("sub-deadband jitter does not move the line", abs(Angles.deg(o.angle!!) - 10.5) < 0.01)
    o = tick(12.0)
    check("medium move (1.5°): old line stays visible but marked stale", o.state == AimState.STALE && abs(Angles.deg(o.angle!!) - 10.5) < 0.01)
    o = tick(12.0); o = tick(12.0); o = tick(12.0); o = tick(12.0)
    check("medium move once settled: recalculated at 12°", o.state == AimState.VISIBLE && abs(Angles.deg(o.angle!!) - 12.0) < 0.01)
    o = tick(25.0)
    check("large move (13°): line disappears", o.state == AimState.HIDDEN)
    o = tick(25.0); o = tick(25.0); o = tick(25.0); o = tick(25.0)
    check("large move once settled: line is back at the new angle", o.state == AimState.VISIBLE && abs(Angles.deg(o.angle!!) - 25.0) < 0.01)
    // balls moving hides everything
    val moved = listOf(Vec2(130.0, 100.0), Vec2(200.0, 150.0))
    t += 100; o = tr.update(t, Angles.rad(25.0), moved, 9.0, s)
    check("balls moving (shot in progress): hidden", o.state == AimState.HIDDEN && o.ballsMoving)
    // aim line lost briefly keeps showing (stale), lost for long hides
    val tr2 = AimTracker(); var t2 = 0L
    fun tick2(d: Double?): TrackerOutput { t2 += 100; return tr2.update(t2, d?.let { Angles.rad(it) }, balls, 9.0, s) }
    tick2(5.0); tick2(5.0); tick2(5.0); tick2(5.0)
    o = tick2(null); check("aim detection drops for 100 ms: line held (stale)", o.state == AimState.STALE)
    repeat(6) { o = tick2(null) }
    check("aim line gone for >450 ms: hidden", o.state == AimState.HIDDEN)
    // live mode follows immediately
    val live = KenoSettings(stabilizeAim = false); val tr3 = AimTracker()
    tr3.update(100, Angles.rad(10.0), balls, 9.0, live)
    o = tr3.update(200, Angles.rad(30.0), balls, 9.0, live)
    check("live mode: follows a large move at once", o.state == AimState.VISIBLE && abs(Angles.deg(o.angle!!) - 30.0) < 0.01)
}

// ---------------------------------------------------------------------------------------------
fun testEndToEnd() {
    println("\n[end to end: pixels -> prediction]")
    val R = 16.0
    // target ball at (900, 380); aim a 25° cut so the ghost ball is at known geometry
    val tgt = SynthBall(900.0, 380.0, "solid", ballColors[1])
    val cutDeg = 25.0
    val cut = Math.toRadians(cutDeg)
    // line of centres direction: pocket direction = towards right-top corner-ish: angle -12° (screen coords, y down)
    val lineAng = Math.toRadians(-12.0)
    val ghost = Vec2(tgt.x - cos(lineAng) * 2 * R, tgt.y - sin(lineAng) * 2 * R)
    // cue ball placed so that the aim direction differs from the line of centres by 'cut'
    val aimAng = lineAng + cut
    val cueStart = Vec2(ghost.x - cos(aimAng) * 160, ghost.y - sin(aimAng) * 160)
    val sc = SynthScene(balls = listOf(SynthBall(cueStart.x, cueStart.y, "cue", Color.WHITE), tgt),
        aimAngle = aimAng, guideLength = 140.0, spinDot = Vec2(0.0, 0.0))
    val (frame, img) = frameOf(sc)
    val analyzer = KenoAnalyzer()
    val s = KenoSettings(spinMode = SpinMode.AUTO, stabilizeAim = false, power = 0.4)
    val res = analyzer.analyze(frame, null, 5000, s)
    println("    status: ${res.status}")
    val pred = res.prediction
    check("analysis produced a prediction from raw pixels", pred != null)
    if (pred == null) return
    val k = frame.scale
    val c = pred.contact
    check("a first contact with the target ball was predicted", c != null)
    if (c != null) {
        val ex = hypot(c.cueAtContact.x * k - ghost.x, c.cueAtContact.y * k - ghost.y)
        println("    ghost-ball centre error: %.2f px (full-res)".format(ex))
        check("predicted ghost-ball position matches the true geometry within 4 px (full-res, ~1/4 ball radius)", ex < 4.0)
        val dir = (c.targetCenter - c.cueAtContact)
        val got = Math.toDegrees(atan2(dir.y, dir.x))
        check("target leaves along the line of centres (%.1f° expected)".format(Math.toDegrees(lineAng)), abs(got - Math.toDegrees(lineAng)) < 1.5, "%.2f°".format(got))
    }
    check("spin widget read from screen as centre (no spin)", res.spin.usable && abs(res.spin.x) < 0.1 && abs(res.spin.y) < 0.1, res.spin.label)

    // same shot with top-spin / back-spin dots on the on-screen spin ball: post-contact cue direction must differ correctly
    fun parting(dot: Vec2): Double {
        val sc2 = SynthScene(balls = sc.balls, aimAngle = aimAng, guideLength = 140.0, spinDot = dot)
        val (f2, _) = frameOf(sc2)
        val r2 = KenoAnalyzer().analyze(f2, null, 5000, s)
        val pr = r2.prediction!!
        val cp = pr.contact!!
        val cue = pr.cue.points
        var ki = 0; var bd = 1e9
        for (i in cue.indices) { val d = cue[i].dist(cp.cueAtContact); if (d < bd) { bd = d; ki = i } }
        var j = ki + 1
        while (j < cue.size - 1 && cue[j].dist(cue[ki]) < 25.0) j++
        val outDir = Math.toDegrees(atan2(cue[j].y - cue[ki].y, cue[j].x - cue[ki].x))
        val tgtDir = Math.toDegrees(atan2(cp.targetCenter.y - cp.cueAtContact.y, cp.targetCenter.x - cp.cueAtContact.x))
        return abs(((outDir - tgtDir + 540) % 360) - 180)
    }
    val aTop = parting(Vec2(0.0, 1.0)); val aNone = parting(Vec2(0.0, 0.0)); val aBack = parting(Vec2(0.0, -1.0))
    println("    angle between target path and cue path after a %.0f° cut: top-spin %.1f°, no spin %.1f°, back-spin %.1f°".format(cutDeg, aTop, aNone, aBack))
    check("top spin read from the screen: cue ball follows through (smaller parting angle than no spin)", aTop < aNone - 1.0)
    check("back spin read from the screen: cue ball pulls away (larger parting angle than no spin)", aBack > aNone + 3.0)
    check("everything is drawn: scene has lines", SceneBuilder.build(res, s).prims.filterIsInstance<PLine>().isNotEmpty())
    // render the final picture
    val comp = frame.argb.clone()
    compositeScene(comp, frame.width, frame.height, SceneBuilder.build(res, s.copy(showDebug = true)))
    savePng(comp, frame.width, frame.height, "end_to_end.png", 2)
    ImageIO.write(img, "png", File(outDir, "end_to_end_raw.png"))
}
