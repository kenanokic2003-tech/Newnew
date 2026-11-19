package com.keno.billiards.core

enum class SpinMode { AUTO, MANUAL, OFF }

/** Every feature can be switched on/off independently. Immutable: the UI replaces it with copy(). */
data class KenoSettings(
    val analysisOn: Boolean = true,
    val showAimLine: Boolean = true,
    /** Extend the line: continue past the first contact / cushions with the full prediction. */
    val extendAim: Boolean = true,
    val showTargetPath: Boolean = true,
    val showCuePath: Boolean = true,
    val showFinalPositions: Boolean = true,
    val showDebug: Boolean = false,
    val spinMode: SpinMode = SpinMode.AUTO,
    /** Manual spin pad: x +right, y +up(top spin), both -1..1. */
    val manualSpinX: Double = 0.0,
    val manualSpinY: Double = 0.0,
    /** 0..1 -> cue ball speed after the strike. Not readable from the screen, so you set it. */
    val power: Double = 0.5,
    /** true: hide on a big aim move and recalculate once settled; false: always follow live. */
    val stabilizeAim: Boolean = true,
    /** Aim changes up to this many degrees keep the line visible. */
    val smallMoveDeg: Double = 0.8,
    /** Move the cushions in (+) or out (-), in analysis pixels, to match the real cushion nose. */
    val cushionAdjustPx: Double = 0.0,
    /** 1.0 = a dot at the rim of the spin ball means a half-ball-radius tip offset. */
    val spinStrength: Double = 1.0,
    val maxCueCushions: Int = 3,
    /** Calibrated location of the game's spin ball (fractions of the screen), or null. */
    val spinWidget: SpinWidget? = null,
) {
    val largeMoveDeg: Double get() = maxOf(2.5, smallMoveDeg * 3.0)
}
