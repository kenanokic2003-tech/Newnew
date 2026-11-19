package com.keno.billiards

import android.content.Context
import com.keno.billiards.core.KenoSettings
import com.keno.billiards.core.SpinMode
import com.keno.billiards.core.SpinWidget

/** Persists [KenoSettings] and the floating button position. */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("keno_settings", Context.MODE_PRIVATE)

    fun load(): KenoSettings {
        val d = KenoSettings()
        val widget = if (p.contains("wdg_cx")) SpinWidget(
            p.getFloat("wdg_cx", 0f).toDouble(), p.getFloat("wdg_cy", 0f).toDouble(), p.getFloat("wdg_r", 0f).toDouble()
        ) else null
        return KenoSettings(
            analysisOn = p.getBoolean("analysisOn", d.analysisOn),
            showAimLine = p.getBoolean("showAimLine", d.showAimLine),
            extendAim = p.getBoolean("extendAim", d.extendAim),
            showTargetPath = p.getBoolean("showTargetPath", d.showTargetPath),
            showCuePath = p.getBoolean("showCuePath", d.showCuePath),
            showFinalPositions = p.getBoolean("showFinal", d.showFinalPositions),
            showDebug = p.getBoolean("showDebug", d.showDebug),
            spinMode = try { SpinMode.valueOf(p.getString("spinMode", d.spinMode.name)!!) } catch (e: Exception) { d.spinMode },
            manualSpinX = p.getFloat("manualX", 0f).toDouble(),
            manualSpinY = p.getFloat("manualY", 0f).toDouble(),
            power = p.getFloat("power", d.power.toFloat()).toDouble(),
            stabilizeAim = p.getBoolean("stabilize", d.stabilizeAim),
            smallMoveDeg = p.getFloat("smallMove", d.smallMoveDeg.toFloat()).toDouble(),
            cushionAdjustPx = p.getFloat("cushionAdj", 0f).toDouble(),
            spinStrength = p.getFloat("spinStrength", d.spinStrength.toFloat()).toDouble(),
            maxCueCushions = p.getInt("maxCushions", d.maxCueCushions),
            spinWidget = widget,
        )
    }

    fun save(s: KenoSettings) {
        val e = p.edit()
        e.putBoolean("analysisOn", s.analysisOn)
        e.putBoolean("showAimLine", s.showAimLine)
        e.putBoolean("extendAim", s.extendAim)
        e.putBoolean("showTargetPath", s.showTargetPath)
        e.putBoolean("showCuePath", s.showCuePath)
        e.putBoolean("showFinal", s.showFinalPositions)
        e.putBoolean("showDebug", s.showDebug)
        e.putString("spinMode", s.spinMode.name)
        e.putFloat("manualX", s.manualSpinX.toFloat())
        e.putFloat("manualY", s.manualSpinY.toFloat())
        e.putFloat("power", s.power.toFloat())
        e.putBoolean("stabilize", s.stabilizeAim)
        e.putFloat("smallMove", s.smallMoveDeg.toFloat())
        e.putFloat("cushionAdj", s.cushionAdjustPx.toFloat())
        e.putFloat("spinStrength", s.spinStrength.toFloat())
        e.putInt("maxCushions", s.maxCueCushions)
        val w = s.spinWidget
        if (w != null) {
            e.putFloat("wdg_cx", w.cx.toFloat()); e.putFloat("wdg_cy", w.cy.toFloat()); e.putFloat("wdg_r", w.r.toFloat())
        } else {
            e.remove("wdg_cx"); e.remove("wdg_cy"); e.remove("wdg_r")
        }
        e.apply()
    }

    fun buttonPos(): Pair<Int, Int> = Pair(p.getInt("btn_x", 24), p.getInt("btn_y", 160))
    fun saveButtonPos(x: Int, y: Int) { p.edit().putInt("btn_x", x).putInt("btn_y", y).apply() }
}
