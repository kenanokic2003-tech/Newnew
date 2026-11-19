package com.keno.billiards.core

import kotlin.math.abs

enum class AimState {
    /** Nothing to show (no aim line, balls moving, or a large aim move not yet settled). */
    HIDDEN,
    /** Prediction is current. */
    VISIBLE,
    /** A small-to-medium aim change happened: the old line stays visible until the aim settles. */
    STALE,
}

class TrackerOutput(val state: AimState, val angle: Double?, val ballsMoving: Boolean)

/**
 * Decides what to show while the player nudges the aim, implementing:
 *  - tiny adjustments  (<= small): the line stays visible and follows smoothly, no flicker;
 *  - medium adjustments (small..large): the old line stays visible (marked stale) until the aim settles;
 *  - large movements (> large): the line disappears, then is recalculated once the aim settled;
 *  - balls moving (a shot in progress): hidden.
 * In "live" mode ([KenoSettings.stabilizeAim] = false) the angle is simply followed.
 * Pure logic with an explicit clock so it can be tested.
 */
class AimTracker {
    private var locked: Double? = null
    private var candidate: Double? = null
    private var candidateSince = 0L
    private var lastAimSeen = Long.MIN_VALUE / 2
    private var lastBalls: List<Vec2>? = null
    private var lastBallMove = Long.MIN_VALUE / 2

    companion object {
        const val SETTLE_MS = 220L
        const val AIM_LOSS_HOLD_MS = 450L
        const val BALL_SETTLE_MS = 400L
        const val JITTER_DEG = 0.25
        const val DEADBAND_DEG = 0.3
    }

    fun reset() { locked = null; candidate = null; lastBalls = null }

    fun update(nowMs: Long, aim: Double?, balls: List<Vec2>, ballRadius: Double, s: KenoSettings): TrackerOutput {
        // ---- balls moving? ----
        val prev = lastBalls
        var moved = false
        if (prev != null) {
            // order-independent: match each ball to its nearest previous position
            for (b in balls) {
                var best = Double.MAX_VALUE
                for (q in prev) { val d = b.dist(q); if (d < best) best = d }
                // unmatched balls (appeared/disappeared through detection noise) are ignored
                if (best > 0.3 * ballRadius && best < 6.0 * ballRadius) { moved = true; break }
            }
        }
        lastBalls = balls
        if (moved) lastBallMove = nowMs
        val ballsMoving = nowMs - lastBallMove < BALL_SETTLE_MS && prev != null
        if (ballsMoving) {
            locked = null; candidate = null
            return TrackerOutput(AimState.HIDDEN, null, true)
        }

        // ---- aim presence ----
        if (aim == null) {
            if (nowMs - lastAimSeen > AIM_LOSS_HOLD_MS) { locked = null; candidate = null }
            val l = locked
            return if (l != null) TrackerOutput(AimState.STALE, l, false) else TrackerOutput(AimState.HIDDEN, null, false)
        }
        lastAimSeen = nowMs

        // ---- is the aim settled? ----
        val cand = candidate
        if (cand == null || abs(Angles.deg(Angles.diff(aim, cand))) > JITTER_DEG) {
            candidate = aim
            candidateSince = nowMs
        }
        val settled = nowMs - candidateSince >= SETTLE_MS

        val l = locked
        if (!s.stabilizeAim) {
            locked = aim
            return TrackerOutput(AimState.VISIBLE, aim, false)
        }
        if (l == null) {
            return if (settled) { locked = aim; TrackerOutput(AimState.VISIBLE, aim, false) }
            else TrackerOutput(AimState.HIDDEN, null, false)
        }
        val dd = abs(Angles.deg(Angles.diff(aim, l)))
        return when {
            dd <= s.smallMoveDeg -> {
                if (dd > DEADBAND_DEG) locked = aim // follow tiny adjustments, ignore detection noise
                TrackerOutput(AimState.VISIBLE, locked, false)
            }
            dd <= s.largeMoveDeg -> {
                if (settled) { locked = aim; TrackerOutput(AimState.VISIBLE, aim, false) }
                else TrackerOutput(AimState.STALE, l, false)
            }
            else -> {
                if (settled) { locked = aim; TrackerOutput(AimState.VISIBLE, aim, false) }
                else { TrackerOutput(AimState.HIDDEN, null, false) }
            }
        }
    }
}
