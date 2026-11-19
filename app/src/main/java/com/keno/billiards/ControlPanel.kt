package com.keno.billiards

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.keno.billiards.core.KenoSettings
import com.keno.billiards.core.SpinMode

/** The expanded KENO panel. Lives in its own small window; the rest of the screen stays touchable for the game. */
class ControlPanel(
    private val ctx: Context,
    private val dp: Float,
    initial: KenoSettings,
    private val onChange: ((KenoSettings) -> KenoSettings) -> Unit,
    private val onCalibrate: () -> Unit,
    private val onAutoLocate: () -> Unit,
    private val onStop: () -> Unit,
    private val onClose: () -> Unit,
) {
    val view: View
    private var updating = false
    private val status = TextView(ctx)
    private val switches = HashMap<String, Switch>()
    private lateinit var spinButtons: Map<SpinMode, Button>
    private lateinit var pad: SpinPadView
    private lateinit var smallLabel: TextView
    private lateinit var powerLabel: TextView
    private lateinit var cushionLabel: TextView
    private lateinit var small: SeekBar
    private lateinit var power: SeekBar
    private lateinit var cushion: SeekBar

    private fun px(v: Float) = (v * dp).toInt()
    private fun sp(t: TextView, size: Float) { t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size); t.setTextColor(Color.WHITE) }

    init {
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(px(10f), px(8f), px(10f), px(10f))

        val head = LinearLayout(ctx)
        head.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(ctx); title.text = "KENO V2"; sp(title, 15f); title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val close = Button(ctx); close.text = "✕"; close.setOnClickListener { onClose() }
        head.addView(close, LinearLayout.LayoutParams(px(44f), px(36f)))
        col.addView(head)

        sp(status, 11f); status.setTextColor(0xFFB0BEC5.toInt()); status.text = "čekam prvi kadar…"
        col.addView(status)

        fun sw(key: String, label: String, get: (KenoSettings) -> Boolean, set: (KenoSettings, Boolean) -> KenoSettings) {
            val s = Switch(ctx); s.text = label; s.setTextColor(Color.WHITE); s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            s.isChecked = get(initial)
            s.setOnCheckedChangeListener { _, v -> if (!updating) onChange { cur -> set(cur, v) } }
            switches[key] = s
            col.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(36f)))
        }
        sw("analysis", "Analiza ekrana (hvatanje)", { it.analysisOn }) { s, v -> s.copy(analysisOn = v) }
        sw("aim", "Linija ciljanja", { it.showAimLine }) { s, v -> s.copy(showAimLine = v) }
        sw("extend", "Produži liniju (cijela putanja)", { it.extendAim }) { s, v -> s.copy(extendAim = v) }
        sw("target", "Putanja ciljne kugle", { it.showTargetPath }) { s, v -> s.copy(showTargetPath = v) }
        sw("cue", "Putanja bijele (sa spinom)", { it.showCuePath }) { s, v -> s.copy(showCuePath = v) }
        sw("final", "Završne pozicije", { it.showFinalPositions }) { s, v -> s.copy(showFinalPositions = v) }
        sw("stab", "Stabilan cilj (sakrij pri velikom pomaku)", { it.stabilizeAim }) { s, v -> s.copy(stabilizeAim = v) }
        sw("debug", "Debug prikaz (sto, kugle, rupe)", { it.showDebug }) { s, v -> s.copy(showDebug = v) }

        fun label(t: String): TextView { val l = TextView(ctx); l.text = t; sp(l, 11f); l.setPadding(0, px(6f), 0, 0); return l }

        smallLabel = label(""); col.addView(smallLabel)
        small = SeekBar(ctx); small.max = 28 // 0.2 .. 3.0 deg
        small.setOnSeekBarChangeListener(listener { v -> onChange { it.copy(smallMoveDeg = 0.2 + v * 0.1) } })
        col.addView(small)

        powerLabel = label(""); col.addView(powerLabel)
        power = SeekBar(ctx); power.max = 100
        power.setOnSeekBarChangeListener(listener { v -> onChange { it.copy(power = v / 100.0) } })
        col.addView(power)

        col.addView(label("Spin bijele kugle"))
        val modeRow = LinearLayout(ctx)
        val map = HashMap<SpinMode, Button>()
        for ((m, t) in listOf(SpinMode.AUTO to "Sa ekrana", SpinMode.MANUAL to "Ručno", SpinMode.OFF to "Bez")) {
            val b = Button(ctx); b.text = t; b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f); b.isAllCaps = false
            b.setOnClickListener { onChange { it.copy(spinMode = m) } }
            map[m] = b
            modeRow.addView(b, LinearLayout.LayoutParams(0, px(40f), 1f))
        }
        spinButtons = map
        col.addView(modeRow)
        pad = SpinPadView(ctx, px(110f))
        pad.onChange = { x, y -> onChange { it.copy(manualSpinX = x, manualSpinY = y) } }
        col.addView(pad, LinearLayout.LayoutParams(px(110f), px(110f)).also { it.gravity = Gravity.CENTER_HORIZONTAL })

        val cal = Button(ctx); cal.text = "Kalibriši spin loptu iz igre"; cal.isAllCaps = false; cal.setOnClickListener { onCalibrate() }
        col.addView(cal)
        val auto = Button(ctx); auto.text = "Pokušaj automatski naći spin loptu"; auto.isAllCaps = false; auto.setOnClickListener { onAutoLocate() }
        col.addView(auto)

        cushionLabel = label(""); col.addView(cushionLabel)
        cushion = SeekBar(ctx); cushion.max = 24 // -12 .. +12 px
        cushion.setOnSeekBarChangeListener(listener { v -> onChange { it.copy(cushionAdjustPx = (v - 12).toDouble()) } })
        col.addView(cushion)

        val stop = Button(ctx); stop.text = "Zaustavi KENO"; stop.isAllCaps = false; stop.setOnClickListener { onStop() }
        col.addView(stop)

        val scroll = ScrollView(ctx)
        scroll.addView(col)
        val bg = GradientDrawable(); bg.setColor(0xF0151A21.toInt()); bg.cornerRadius = px(12f).toFloat()
        scroll.background = bg
        view = scroll
        refresh(initial)
    }

    private fun listener(f: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser && !updating) f(p) }
        override fun onStartTrackingTouch(s: SeekBar) {}
        override fun onStopTrackingTouch(s: SeekBar) {}
    }

    /** Push settings into the widgets without echoing them back as changes. */
    fun refresh(s: KenoSettings) {
        updating = true
        switches["analysis"]?.isChecked = s.analysisOn
        switches["aim"]?.isChecked = s.showAimLine
        switches["extend"]?.isChecked = s.extendAim
        switches["target"]?.isChecked = s.showTargetPath
        switches["cue"]?.isChecked = s.showCuePath
        switches["final"]?.isChecked = s.showFinalPositions
        switches["stab"]?.isChecked = s.stabilizeAim
        switches["debug"]?.isChecked = s.showDebug
        small.progress = Math.round((s.smallMoveDeg - 0.2) / 0.1).toInt().coerceIn(0, 28)
        smallLabel.text = "Mali pomak zadržava liniju: do %.1f°".format(s.smallMoveDeg)
        power.progress = Math.round(s.power * 100).toInt()
        powerLabel.text = "Snaga udarca (ne čita se s ekrana): ${Math.round(s.power * 100)}%"
        cushion.progress = (Math.round(s.cushionAdjustPx).toInt() + 12).coerceIn(0, 24)
        cushionLabel.text = "Podešavanje ivica stola: %+d px".format(Math.round(s.cushionAdjustPx).toInt())
        for ((m, b) in spinButtons) b.alpha = if (m == s.spinMode) 1f else 0.5f
        pad.visibility = if (s.spinMode == SpinMode.MANUAL) View.VISIBLE else View.GONE
        pad.x = s.manualSpinX; pad.y = s.manualSpinY; pad.invalidate()
        updating = false
    }

    fun setStatus(t: String) { status.text = t }
}
