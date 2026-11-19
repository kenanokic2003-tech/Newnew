package com.keno.billiards.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Rigid-ball billiards physics in SI units with a right-handed frame: X right, Y UP, Z up.
 * (The screen has Y down; ShotPredictor converts.)
 *
 * Model
 *  - Each ball has linear velocity v and angular velocity w (wx, wy horizontal, wz vertical).
 *  - Velocity of the contact point with the cloth: u = (vx - R wy, vy + R wx).
 *    While |u| > 0 the ball SLIDES: friction decelerates v and spins w up/down until u = 0
 *    (closed form, du/dt = -3.5 mu g u_hat). Then it ROLLS with a small rolling resistance.
 *    So top spin makes the cue ball keep going (follow), back spin pulls it back (draw).
 *  - Ball-ball: equal masses, restitution e along the line of centres, tangential velocity and spin
 *    unchanged (no throw). Spin is kept, so after impact the ball re-enters the sliding phase.
 *  - Cushion: restitution on the normal component plus Coulomb friction on the contact point, which
 *    couples side spin (wz) to the tangential velocity -> side spin changes the rebound angle.
 *  - Pockets: a ball whose centre gets within the capture radius of a pocket is potted.
 *
 * Not modelled: ball-ball throw / spin transfer, cue elevation (masse), squirt, cloth speed variance,
 * and the game's own cheats. Predictions are therefore approximate for the real game.
 */
class PhysicsParams(
    val ballRadius: Double = 0.02855,
    val g: Double = 9.81,
    val muSlide: Double = 0.2,
    val muRoll: Double = 0.012,
    val eBall: Double = 0.95,
    val eCushion: Double = 0.78,
    val muCushion: Double = 0.18,
    val sideSpinDecay: Double = 0.35,
    val dt: Double = 0.001,
    val maxTime: Double = 12.0,
    val maxCueCushions: Int = 3,
    val cornerCapture: Double = 1.9,
    val sideCapture: Double = 1.7,
    /** Height of the cushion contact point above the ball centre, in ball radii (real tables ~0.27). */
    val cushionNoseHeight: Double = 0.27,
    val pathSampleSteps: Int = 8,
)

class SimBall(var x: Double, var y: Double) {
    var vx = 0.0; var vy = 0.0
    var wx = 0.0; var wy = 0.0; var wz = 0.0
    var active = true
    var potted = false
    var pocketIndex = -1
    var px = x; var py = y // previous position within a step
    val path = ArrayList<Vec2>().also { it.add(Vec2(x, y)) }
    var cushions = 0
    var moved = false
    /** Frozen because it exceeded the cushion limit: the path ends there, it is NOT a rest position. */
    var truncated = false
}

/** Cushion-to-cushion rectangle in metres (Y up) and the six pocket centres. */
class SimTable(val left: Double, val right: Double, val bottom: Double, val top: Double, val pockets: List<Vec2>, val pocketIsCorner: List<Boolean>)

class Contact(val cuePos: Vec2, val targetIndex: Int, val targetPos: Vec2)

class SimResult(
    val balls: List<SimBall>,
    val firstContact: Contact?,
    val cueCushionsBeforeContact: Int,
    val time: Double,
)

object Physics {

    /** Give the cue ball its initial velocity and spin. d = unit shot direction (Y up). a,b in metres. */
    fun strike(ball: SimBall, d: Vec2, v0: Double, sideOffsetRight: Double, topOffset: Double, p: PhysicsParams) {
        val R = p.ballRadius
        ball.vx = d.x * v0; ball.vy = d.y * v0
        val k = 5.0 / (2.0 * R * R) * v0
        // top/back spin: rotate about the axis perpendicular to d. natural roll <=> offset = 0.4 R
        ball.wx = k * topOffset * (-d.y)
        ball.wy = k * topOffset * (d.x)
        // side spin: right English (offset > 0) = clockwise seen from above = negative wz
        ball.wz = -k * sideOffsetRight
    }

    fun simulate(balls: List<SimBall>, cueIdx: Int, table: SimTable, p: PhysicsParams): SimResult {
        val R = p.ballRadius
        val dt = p.dt
        var t = 0.0
        var first: Contact? = null
        var cushionsBeforeContact = 0
        var stepNo = 0
        val n = balls.size
        val cue = balls[cueIdx]

        while (t < p.maxTime) {
            var anyMoving = false
            for (b in balls) {
                if (!b.active) continue
                b.px = b.x; b.py = b.y
                step(b, dt, p)
                if (isMoving(b, R)) anyMoving = true
            }
            // pockets, then cushions
            for ((bi, b) in balls.withIndex()) {
                if (!b.active) continue
                if (checkPocket(b, table, p)) continue
                cushion(b, table, p, dt, bi == cueIdx) { 
                    if (bi == cueIdx && first == null) cushionsBeforeContact++ 
                }
            }
            // ball-ball
            for (i in 0 until n) {
                val a = balls[i]
                if (!a.active) continue
                for (j in i + 1 until n) {
                    val c = balls[j]
                    if (!c.active) continue
                    if (!(isMoving(a, R) || isMoving(c, R))) continue
                    val dx = c.x - a.x; val dy = c.y - a.y
                    if (dx * dx + dy * dy >= 4 * R * R) continue
                    if (collide(a, c, R, dt, p)) {
                        if (first == null && (i == cueIdx || j == cueIdx)) {
                            val tIdx = if (i == cueIdx) j else i
                            // collide() appended the exact contact-instant centres as the last path points
                            val cueBall = if (i == cueIdx) a else c
                            val tgtBall = if (i == cueIdx) c else a
                            val cp = cueBall.path.last()
                            val tp = tgtBall.path.last()
                            first = Contact(cp, tIdx, tp)
                        }
                    }
                }
            }
            stepNo++
            if (stepNo % p.pathSampleSteps == 0) {
                for (b in balls) if (b.active && isMoving(b, R)) b.path.add(Vec2(b.x, b.y))
            }
            t += dt
            if (!anyMoving) break
        }
        for (b in balls) {
            if (b.active || b.potted) {
                val last = b.path.last()
                if (hypot(last.x - b.x, last.y - b.y) > 1e-6) b.path.add(Vec2(b.x, b.y))
            }
        }
        for (b in balls) {
            val simp = simplify(b.path, 0.0004)
            b.path.clear(); b.path.addAll(simp)
        }
        return SimResult(balls, first, cushionsBeforeContact, t)
    }

    /** Ramer-Douglas-Peucker: keeps corners (bounces, impacts) and curve shape, drops redundant points. */
    fun simplify(pts: List<Vec2>, eps: Double): List<Vec2> {
        // drop consecutive duplicates first
        val d = ArrayList<Vec2>()
        for (q in pts) if (d.isEmpty() || hypot(q.x - d.last().x, q.y - d.last().y) > 1e-7) d.add(q)
        if (d.size < 3) return d
        val keep = BooleanArray(d.size)
        keep[0] = true; keep[d.size - 1] = true
        val stack = ArrayList<IntArray>()
        stack.add(intArrayOf(0, d.size - 1))
        while (stack.isNotEmpty()) {
            val seg = stack.removeAt(stack.size - 1)
            val a = d[seg[0]]; val c = d[seg[1]]
            var best = -1.0; var bi = -1
            val dx = c.x - a.x; val dy = c.y - a.y
            val len = hypot(dx, dy)
            for (i in seg[0] + 1 until seg[1]) {
                val q = d[i]
                val dist = if (len < 1e-12) hypot(q.x - a.x, q.y - a.y) else abs(dx * (a.y - q.y) - (a.x - q.x) * dy) / len
                if (dist > best) { best = dist; bi = i }
            }
            if (best > eps && bi >= 0) {
                keep[bi] = true
                stack.add(intArrayOf(seg[0], bi)); stack.add(intArrayOf(bi, seg[1]))
            }
        }
        val out = ArrayList<Vec2>()
        for (i in d.indices) if (keep[i]) out.add(d[i])
        return out
    }

    private fun isMoving(b: SimBall, R: Double): Boolean {
        if (!b.active) return false
        val ux = b.vx - R * b.wy
        val uy = b.vy + R * b.wx
        return hypot(b.vx, b.vy) > 1e-3 || hypot(ux, uy) > 1e-3
    }

    private fun step(b: SimBall, dt: Double, p: PhysicsParams) {
        val R = p.ballRadius
        val ux = b.vx - R * b.wy
        val uy = b.vy + R * b.wx
        val um = hypot(ux, uy)
        var remaining = dt
        if (um > 1e-4) {
            // sliding phase
            val ax = ux / um; val ay = uy / um
            val tSlide = um / (3.5 * p.muSlide * p.g)
            val h = min(dt, tSlide)
            val mg = p.muSlide * p.g
            b.x += b.vx * h - 0.5 * mg * ax * h * h
            b.y += b.vy * h - 0.5 * mg * ay * h * h
            b.vx -= mg * ax * h
            b.vy -= mg * ay * h
            val kw = 5.0 * mg / (2.0 * R)
            b.wx += kw * (-ay) * h
            b.wy += kw * (ax) * h
            remaining = dt - h
            if (remaining > 1e-9) {
                // slide ended inside this step: snap exactly to rolling
                b.wy = b.vx / R
                b.wx = -b.vy / R
            }
        }
        if (remaining > 1e-9) {
            val sp = hypot(b.vx, b.vy)
            if (sp > 1e-6) {
                val dec = p.muRoll * p.g * remaining
                if (dec >= sp) {
                    val ts = sp / (p.muRoll * p.g)
                    b.x += b.vx / sp * (sp * ts - 0.5 * p.muRoll * p.g * ts * ts)
                    b.y += b.vy / sp * (sp * ts - 0.5 * p.muRoll * p.g * ts * ts)
                    b.vx = 0.0; b.vy = 0.0; b.wx = 0.0; b.wy = 0.0
                } else {
                    val nx = b.vx / sp; val ny = b.vy / sp
                    b.x += nx * (sp * remaining - 0.5 * p.muRoll * p.g * remaining * remaining)
                    b.y += ny * (sp * remaining - 0.5 * p.muRoll * p.g * remaining * remaining)
                    b.vx -= nx * dec; b.vy -= ny * dec
                    b.wy = b.vx / R; b.wx = -b.vy / R
                }
            } else {
                b.vx = 0.0; b.vy = 0.0
                b.wx = 0.0; b.wy = 0.0
            }
        }
        b.wz *= exp(-p.sideSpinDecay * dt)
        if (hypot(b.vx, b.vy) < 1e-4 && hypot(b.vx - R * b.wy, b.vy + R * b.wx) < 1e-4) {
            b.vx = 0.0; b.vy = 0.0; b.wx = 0.0; b.wy = 0.0
        }
        if (hypot(b.vx, b.vy) > 1e-3) b.moved = true
    }

    private fun checkPocket(b: SimBall, t: SimTable, p: PhysicsParams): Boolean {
        for ((i, pk) in t.pockets.withIndex()) {
            val cap = (if (t.pocketIsCorner[i]) p.cornerCapture else p.sideCapture) * p.ballRadius
            if (hypot(b.x - pk.x, b.y - pk.y) < cap) {
                b.potted = true
                b.pocketIndex = i
                b.active = false
                b.path.add(Vec2(pk.x, pk.y))
                b.vx = 0.0; b.vy = 0.0
                return true
            }
        }
        return false
    }

    /** Reflect from the four cushions with spin-dependent friction. */
    private fun cushion(b: SimBall, t: SimTable, p: PhysicsParams, dt: Double, isCue: Boolean, onHit: () -> Unit) {
        val R = p.ballRadius
        val xmin = t.left + R; val xmax = t.right - R
        val ymin = t.bottom + R; val ymax = t.top - R
        // inward normals: left cushion (1,0), right (-1,0), bottom (0,1), top (0,-1)
        if (b.x < xmin && b.vx < 0) hit(b, 1.0, 0.0, xmin, true, p, dt, isCue, onHit)
        else if (b.x > xmax && b.vx > 0) hit(b, -1.0, 0.0, xmax, true, p, dt, isCue, onHit)
        if (b.y < ymin && b.vy < 0) hit(b, 0.0, 1.0, ymin, false, p, dt, isCue, onHit)
        else if (b.y > ymax && b.vy > 0) hit(b, 0.0, -1.0, ymax, false, p, dt, isCue, onHit)
    }

    private fun hit(b: SimBall, nx: Double, ny: Double, bound: Double, vertical: Boolean, p: PhysicsParams, dt: Double, isCue: Boolean, onHit: () -> Unit) {
        val R = p.ballRadius
        // exact crossing time inside this step (fraction s of the step travelled before the bound)
        val prev = if (vertical) b.px else b.py
        val now = if (vertical) b.x else b.y
        val denom = now - prev
        val s = if (abs(denom) < 1e-12) 1.0 else ((bound - prev) / denom).coerceIn(0.0, 1.0)
        val cx = b.px + (b.x - b.px) * s
        val cy = b.py + (b.y - b.py) * s
        // contact tangent t = k x n = (-ny, nx)
        val tx = -ny; val ty = nx
        val vn = b.vx * nx + b.vy * ny // negative: moving into the cushion
        val vt = b.vx * tx + b.vy * ty
        val vIn = -vn
        val ut = vt - R * b.wz
        val jnPerM = (1.0 + p.eCushion) * vIn
        val maxJt = p.muCushion * jnPerM
        val needJt = abs(ut) / 3.5
        val jt = -(if (ut >= 0) 1.0 else -1.0) * min(maxJt, needJt)
        val newVn = p.eCushion * vIn
        val newVt = vt + jt
        b.vx = nx * newVn + tx * newVt
        b.vy = ny * newVn + ty * newVt
        b.wz -= 2.5 * jt / R
        // the cushion nose touches the ball above its centre: the normal impulse also spins it about the tangent axis
        val dwt = 2.5 * (p.cushionNoseHeight * R) * jnPerM / (R * R)
        b.wx += dwt * tx
        b.wy += dwt * ty
        // continue for the rest of the step with the new velocity
        val rest = (1.0 - s) * dt
        b.x = cx + b.vx * rest
        b.y = cy + b.vy * rest
        // keep the ball inside (numerical safety)
        if (vertical) { if (nx > 0 && b.x < bound) b.x = bound; if (nx < 0 && b.x > bound) b.x = bound }
        else { if (ny > 0 && b.y < bound) b.y = bound; if (ny < 0 && b.y > bound) b.y = bound }
        b.path.add(Vec2(cx, cy))
        b.cushions++
        onHit()
        val lim = if (isCue) p.maxCueCushions else p.maxCueCushions + 1
        if (b.cushions >= lim) {
            b.vx = 0.0; b.vy = 0.0; b.wx = 0.0; b.wy = 0.0; b.wz = 0.0
            b.truncated = true
            b.x = cx; b.y = cy
        }
    }

    /** Resolve an overlapping pair at the exact instant of contact. Returns true if an impulse was applied. */
    private fun collide(a: SimBall, c: SimBall, R: Double, dt: Double, p: PhysicsParams): Boolean {
        // find s in [0,1] where the distance equals 2R using the positions at start/end of the step
        val rx0 = c.px - a.px; val ry0 = c.py - a.py
        val rx1 = c.x - a.x; val ry1 = c.y - a.y
        val ddx = rx1 - rx0; val ddy = ry1 - ry0
        val A = ddx * ddx + ddy * ddy
        val B = 2 * (rx0 * ddx + ry0 * ddy)
        val C = rx0 * rx0 + ry0 * ry0 - 4 * R * R
        var s = 1.0
        if (A > 1e-18 && C > 0) {
            val disc = B * B - 4 * A * C
            if (disc >= 0) {
                val s1 = (-B - sqrt(disc)) / (2 * A)
                if (s1 in 0.0..1.0) s = s1
            }
        } else if (C <= 0) {
            s = 0.0 // already overlapping at the start (e.g. resting contact); resolve immediately
        }
        val ax = a.px + (a.x - a.px) * s; val ay = a.py + (a.y - a.py) * s
        val cx = c.px + (c.x - c.px) * s; val cy = c.py + (c.y - c.py) * s
        var nx = cx - ax; var ny = cy - ay
        val d = hypot(nx, ny)
        if (d < 1e-9) return false
        nx /= d; ny /= d
        val vrel = (a.vx - c.vx) * nx + (a.vy - c.vy) * ny // >0 means approaching
        if (vrel <= 0) {
            // separating already: just push apart if overlapping
            return false
        }
        val imp = (1.0 + p.eBall) / 2.0 * vrel
        a.vx -= imp * nx; a.vy -= imp * ny
        c.vx += imp * nx; c.vy += imp * ny
        a.moved = true; c.moved = true
        val rest = (1.0 - s) * dt
        a.x = ax + a.vx * rest; a.y = ay + a.vy * rest
        c.x = cx + c.vx * rest; c.y = cy + c.vy * rest
        // make sure they do not remain overlapped
        val ex = c.x - a.x; val ey = c.y - a.y
        val ed = hypot(ex, ey)
        if (ed < 2 * R && ed > 1e-9) {
            val push = (2 * R - ed) / 2
            a.x -= ex / ed * push; a.y -= ey / ed * push
            c.x += ex / ed * push; c.y += ey / ed * push
        }
        a.path.add(Vec2(ax, ay)); c.path.add(Vec2(cx, cy))
        return true
    }
}
