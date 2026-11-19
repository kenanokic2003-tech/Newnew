package com.keno.billiards

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import com.keno.billiards.core.PCircle
import com.keno.billiards.core.PLine
import com.keno.billiards.core.PText
import com.keno.billiards.core.Scene
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Draw-only layer. It lives in its own full-screen window flagged FLAG_NOT_TOUCHABLE, so every touch goes
 * straight through to the game. It has NO controls: those are in separate small windows.
 * Primitives are in analysis-frame pixels; [scale] converts them to real screen pixels. Drawing is done in
 * absolute screen coordinates (window offset compensated), so it lines up with the captured frame exactly.
 */
class AnalysisOverlayView(context: Context) : View(context) {
    @Volatile private var scene: Scene = Scene.EMPTY
    private var scale = 1
    private val loc = IntArray(2)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(3f, 1f, 1f, Color.BLACK); isFakeBoldText = true }
    private val path = Path()

    fun setScene(s: Scene, scale: Int) {
        this.scene = s
        this.scale = scale
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val s = scene
        if (s.prims.isEmpty()) return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(-loc[0].toFloat(), -loc[1].toFloat())
        canvas.scale(scale.toFloat(), scale.toFloat())
        for (p in s.prims) {
            when (p) {
                is PLine -> {
                    if (p.pts.size < 2) continue
                    line.color = p.color
                    line.strokeWidth = p.width.toFloat()
                    line.pathEffect = if (p.dashed) DashPathEffect(floatArrayOf(7f, 5f), 0f) else null
                    path.reset()
                    path.moveTo(p.pts[0].x.toFloat(), p.pts[0].y.toFloat())
                    for (i in 1 until p.pts.size) path.lineTo(p.pts[i].x.toFloat(), p.pts[i].y.toFloat())
                    canvas.drawPath(path, line)
                }
                is PCircle -> {
                    if (p.filled) { fill.color = p.color; canvas.drawCircle(p.c.x.toFloat(), p.c.y.toFloat(), p.r.toFloat(), fill) }
                    else { line.color = p.color; line.strokeWidth = p.width.toFloat(); line.pathEffect = null
                        canvas.drawCircle(p.c.x.toFloat(), p.c.y.toFloat(), p.r.toFloat(), line) }
                }
                is PText -> {
                    text.color = p.color
                    text.textSize = p.size.toFloat()
                    canvas.drawText(p.text, p.at.x.toFloat(), p.at.y.toFloat(), text)
                }
            }
        }
        canvas.restore()
    }
}

/** The small round "K" button. Tap = open/close the control panel, drag = move it. Interactive on purpose. */
class FloatingButtonView(context: Context, private val sizePx: Int) : View(context) {
    var active = true
        set(v) { field = v; invalidate() }
    var onTap: (() -> Unit)? = null
    var onDrag: ((dx: Int, dy: Int) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sizePx * 0.06f; color = Color.WHITE }
    private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true; textSize = sizePx * 0.5f }
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(sizePx, sizePx) }

    override fun onDraw(c: Canvas) {
        bg.color = if (active) 0xE6118A5A.toInt() else 0xE6555B66.toInt()
        val r = sizePx / 2f
        c.drawCircle(r, r, r - 2f, bg)
        c.drawCircle(r, r, r - 2f, ring)
        c.drawText("K", r, r + letter.textSize * 0.35f, letter)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; lastX = e.rawX; lastY = e.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) dragging = true
                if (dragging) {
                    onDrag?.invoke((e.rawX - lastX).toInt(), (e.rawY - lastY).toInt())
                    lastX = e.rawX; lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> { if (dragging) onDragEnd?.invoke() else onTap?.invoke(); dragging = false }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}

/** Manual spin selector: a ball you touch where the cue tip would hit. x +right, y +up(top spin). */
class SpinPadView(context: Context, private val sizePx: Int) : View(context) {
    var x = 0.0
    var y = 0.0
    var onChange: ((Double, Double) -> Unit)? = null
    private val ball = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF888888.toInt(); strokeWidth = 3f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD7191E.toInt() }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; strokeWidth = 2f }

    override fun onMeasure(w: Int, h: Int) { setMeasuredDimension(sizePx, sizePx) }

    override fun onDraw(c: Canvas) {
        val r = sizePx / 2f
        c.drawCircle(r, r, r - 3f, ball)
        c.drawCircle(r, r, r - 3f, edge)
        c.drawLine(r, 6f, r, sizePx - 6f, cross)
        c.drawLine(6f, r, sizePx - 6f, r, cross)
        c.drawCircle(r + (x * (r - 10f)).toFloat(), r - (y * (r - 10f)).toFloat(), r * 0.13f, dot)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val r = sizePx / 2f
        var nx = ((e.x - r) / (r - 10f)).toDouble()
        var ny = (-(e.y - r) / (r - 10f)).toDouble()
        val m = hypot(nx, ny)
        if (m > 1.0) { nx /= m; ny /= m }
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            parent?.requestDisallowInterceptTouchEvent(true)
            x = nx; y = ny; invalidate(); onChange?.invoke(x, y)
        }
        return true
    }
}

/**
 * Full-screen, touchable, TEMPORARY window used only while the user places a ring on the game's own spin
 * ball. While it is up, the game cannot be touched; "Gotovo" / "Otkaži" closes it.
 */
class CalibrationView(
    context: Context,
    private var cx: Float, private var cy: Float, private var r: Float,
    private val dp: Float,
    private val onDone: (Float, Float, Float) -> Unit,
    private val onCancel: () -> Unit,
) : FrameLayout(context) {
    private val dim = Paint().apply { color = 0x55000000 }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFF9800.toInt(); strokeWidth = 3f * dp }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF9800.toInt(); strokeWidth = 1.5f * dp }
    private val loc = IntArray(2)
    private var dragging = false
    private var offX = 0f; private var offY = 0f

    init {
        setWillNotDraw(false)
        val col = android.widget.LinearLayout(context)
        col.orientation = android.widget.LinearLayout.VERTICAL
        col.setBackgroundColor(0xEE1A1F26.toInt())
        col.setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (8 * dp).toInt())
        val hint = android.widget.TextView(context)
        hint.text = "Prevuci narandžasti krug preko spin lopte u igri (bijela lopta s crvenom tačkom).\nKrug treba da prati rub lopte. Dugmad +/- mijenjaju veličinu."
        hint.setTextColor(Color.WHITE); hint.textSize = 12f
        col.addView(hint)
        val row = android.widget.LinearLayout(context)
        row.orientation = android.widget.LinearLayout.HORIZONTAL
        fun btn(t: String, a: () -> Unit) = android.widget.Button(context).apply { text = t; setOnClickListener { a() } }
        row.addView(btn("−") { r = max(8f * dp, r * 0.92f); invalidate() })
        row.addView(btn("+") { r = min(120f * dp, r * 1.08f); invalidate() })
        row.addView(btn("Gotovo") { onDone(cx, cy, r) })
        row.addView(btn("Otkaži") { onCancel() })
        col.addView(row)
        addView(col, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL))
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        getLocationOnScreen(loc)
        c.save(); c.translate(-loc[0].toFloat(), -loc[1].toFloat())
        c.drawCircle(cx, cy, r, ringPaint)
        c.drawLine(cx - r * 1.2f, cy, cx + r * 1.2f, cy, cross)
        c.drawLine(cx, cy - r * 1.2f, cx, cy + r * 1.2f, cross)
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragging = true; offX = cx - e.rawX; offY = cy - e.rawY; cx = e.rawX + offX; cy = e.rawY + offY }
            MotionEvent.ACTION_MOVE -> if (dragging) { cx = e.rawX + offX; cy = e.rawY + offY; invalidate() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        // first touch jumps the ring to the finger, which is the fastest way to place it
        if (e.actionMasked == MotionEvent.ACTION_DOWN) { cx = e.rawX; cy = e.rawY; offX = 0f; offY = 0f; invalidate() }
        return true
    }
}
