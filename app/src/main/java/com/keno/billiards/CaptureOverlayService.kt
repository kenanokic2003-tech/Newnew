package com.keno.billiards

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.keno.billiards.core.Frame
import com.keno.billiards.core.FrameConverter
import com.keno.billiards.core.KenoAnalyzer
import com.keno.billiards.core.KenoSettings
import com.keno.billiards.core.Scene
import com.keno.billiards.core.SceneBuilder
import com.keno.billiards.core.SpinWidget

/**
 * Window layout (all TYPE_APPLICATION_OVERLAY):
 *  1. analysis layer  - full screen, FLAG_NOT_TOUCHABLE: draws only, every touch reaches the game
 *  2. K button        - small, touchable, draggable
 *  3. control panel   - small, touchable, only while expanded
 *  4. calibration     - full screen, touchable, only while the user places the spin-ball ring
 * Analysis is purely visual: it reads screenshots delivered by MediaProjection. Nothing touches the game process.
 */
class CaptureOverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.keno.billiards.STOP"
        const val ACTION_TOGGLE_PANEL = "com.keno.billiards.TOGGLE_PANEL"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"
        private const val CHANNEL = "keno_capture"
        private const val NOTIF_ID = 77
        private const val MIN_INTERVAL_MS = 110L
        @Volatile var running = false
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var store: SettingsStore
    private var dp = 1f

    @Volatile private var settings = KenoSettings()
    @Volatile private var scene: Scene = Scene.EMPTY
    @Volatile private var extraRects: List<IntArray> = emptyList() // button/panel bounds, real screen px
    @Volatile private var calibrating = false

    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var thread: HandlerThread? = null
    private val analyzer = KenoAnalyzer()

    private var analysisView: AnalysisOverlayView? = null
    private var buttonView: FloatingButtonView? = null
    private var buttonParams: WindowManager.LayoutParams? = null
    private var panel: ControlPanel? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var calibView: CalibrationView? = null

    // analysis-thread state
    private var frame: Frame? = null
    private var mask: BooleanArray? = null
    private var lastProcessed = 0L
    private var lastSizeCheck = 0L
    private var lastStatusPost = 0L
    private var lastFrameW = 1
    private var lastFrameH = 1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        when (intent.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_TOGGLE_PANEL -> { main.post { togglePanel() }; return START_NOT_STICKY }
        }
        if (running) return START_NOT_STICKY

        // 1. Foreground first: required within seconds, and on Android 14+ BEFORE getMediaProjection().
        startForegroundCompat()

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (code != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }

        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            store = SettingsStore(this)
            settings = store.load()
            dp = resources.displayMetrics.density

            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mgr.getMediaProjection(code, data) ?: throw IllegalStateException("MediaProjection nije dostupan")
            projection = proj

            val t = HandlerThread("keno-analysis").also { it.start() }
            thread = t
            val (w, h, dpi) = realDisplay()
            // 2. ScreenCapturer registers MediaProjection.Callback BEFORE createVirtualDisplay (Android 14 rule)
            val cap = ScreenCapturer(proj, Handler(t.looper), dpi, { main.post { stopSelf() } }) { img -> onImage(img) }
            capturer = cap
            cap.start(w, h)

            showWindows()
            running = true
        } catch (e: Exception) {
            Toast.makeText(this, "KENO nije mogao pokrenuti snimanje ekrana: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- windows

    private fun overlayType() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun showWindows() {
        // --- full-screen analysis layer: draws only, never touchable ---
        val av = AnalysisOverlayView(this)
        val ap = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        ap.gravity = Gravity.TOP or Gravity.START
        // Android 12+ blocks touches that pass through an overlay window whose opacity is above 0.8
        ap.alpha = 0.8f
        if (Build.VERSION.SDK_INT >= 30) ap.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else if (Build.VERSION.SDK_INT >= 28) ap.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        wm.addView(av, ap)
        analysisView = av

        // --- small K button: interactive ---
        val size = (46 * dp).toInt()
        val bv = FloatingButtonView(this, size)
        bv.active = settings.analysisOn
        val (bx, by) = store.buttonPos()
        val bp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        bp.gravity = Gravity.TOP or Gravity.START
        bp.x = bx; bp.y = by
        bv.onTap = { togglePanel() }
        bv.onDrag = { dx, dy ->
            val (w, h, _) = realDisplay()
            bp.x = (bp.x + dx).coerceIn(0, maxOf(0, w - size))
            bp.y = (bp.y + dy).coerceIn(0, maxOf(0, h - size))
            try { wm.updateViewLayout(bv, bp) } catch (_: Exception) {}
            if (panel != null) placePanel()
            updateExtraRects()
        }
        bv.onDragEnd = { store.saveButtonPos(bp.x, bp.y) }
        wm.addView(bv, bp)
        buttonView = bv
        buttonParams = bp
        bv.post { updateExtraRects() }
    }

    private fun togglePanel() {
        if (panel != null) { hidePanel(); return }
        val ctx = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val p = ControlPanel(ctx, dp, settings,
            onChange = { f -> updateSettings(f) },
            onCalibrate = { startCalibration() },
            onAutoLocate = { requestAutoLocate() },
            onStop = { stopSelf() },
            onClose = { hidePanel() })
        val (_, h, _) = realDisplay()
        val pp = WindowManager.LayoutParams(
            (290 * dp).toInt(), (h * 0.86f).toInt(), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        pp.gravity = Gravity.TOP or Gravity.START
        panel = p; panelParams = pp
        placePanel()
        wm.addView(p.view, pp)
        p.view.post { updateExtraRects() }
    }

    /** Put the panel next to the K button, on whichever side has room. */
    private fun placePanel() {
        val pp = panelParams ?: return
        val bp = buttonParams ?: return
        val (w, h, _) = realDisplay()
        val bw = (46 * dp).toInt()
        pp.x = if (bp.x + bw + pp.width + 8 <= w) bp.x + bw + 8 else maxOf(0, bp.x - pp.width - 8)
        pp.y = bp.y.coerceIn(0, maxOf(0, h - pp.height))
        panel?.let { try { wm.updateViewLayout(it.view, pp) } catch (_: Exception) {} }
    }

    private fun hidePanel() {
        panel?.let { try { wm.removeView(it.view) } catch (_: Exception) {} }
        panel = null; panelParams = null
        updateExtraRects()
    }

    private fun updateExtraRects() {
        val list = ArrayList<IntArray>()
        val loc = IntArray(2)
        buttonView?.let { v -> v.getLocationOnScreen(loc); list.add(intArrayOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)) }
        panel?.view?.let { v -> v.getLocationOnScreen(loc); list.add(intArrayOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)) }
        extraRects = list
    }

    // ---------------------------------------------------------------- settings / calibration

    private fun updateSettings(f: (KenoSettings) -> KenoSettings) {
        val n = f(settings)
        settings = n
        store.save(n)
        buttonView?.active = n.analysisOn
        panel?.refresh(n)
        if (!n.analysisOn) { scene = Scene.EMPTY; analysisView?.setScene(Scene.EMPTY, 1) }
    }

    private fun startCalibration() {
        if (calibView != null) return
        hidePanel()
        calibrating = true
        scene = Scene.EMPTY; analysisView?.setScene(Scene.EMPTY, 1)
        val (w, h, _) = realDisplay()
        val s = settings.spinWidget
        val cx = (s?.cx ?: 0.12) * w
        val cy = (s?.cy ?: 0.78) * h
        val r = (s?.r ?: 0.06) * h
        val cv = CalibrationView(ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault), cx.toFloat(), cy.toFloat(), r.toFloat(), dp,
            onDone = { x, y, rr ->
                val sw = SpinWidget((x / w).toDouble(), (y / h).toDouble(), (rr / h).toDouble())
                updateSettings { it.copy(spinWidget = sw) }
                endCalibration()
                Toast.makeText(this, "Spin lopta sačuvana.", Toast.LENGTH_SHORT).show()
            },
            onCancel = { endCalibration() })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 30) lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        wm.addView(cv, lp)
        calibView = cv
    }

    private fun endCalibration() {
        calibView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        calibView = null
        calibrating = false
    }

    private fun requestAutoLocate() {
        if (!settings.analysisOn) { Toast.makeText(this, "Prvo uključi analizu ekrana.", Toast.LENGTH_SHORT).show(); return }
        analyzer.autoLocateDone = false
        analyzer.autoLocateRequested = true
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                if (analyzer.autoLocateDone) {
                    val f = analyzer.autoLocateOutcome
                    if (f != null) {
                        val fw = lastFrameW.toDouble(); val fh = lastFrameH.toDouble()
                        updateSettings { it.copy(spinWidget = SpinWidget(f.cx / fw, f.cy / fh, f.r / fh), spinMode = com.keno.billiards.core.SpinMode.AUTO) }
                        Toast.makeText(this@CaptureOverlayService, "Spin lopta pronađena. Provjeri u Debug prikazu (narandžasti krug).", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@CaptureOverlayService, "Nije pronađena (bijela lopta s crvenom tačkom). Koristi kalibraciju.", Toast.LENGTH_LONG).show()
                    }
                } else if (++tries < 15) main.postDelayed(this, 300) else {
                    Toast.makeText(this@CaptureOverlayService, "Nema kadra za analizu. Otvori igru i probaj ponovo.", Toast.LENGTH_LONG).show()
                }
            }
        }
        main.postDelayed(poll, 300)
    }

    // ---------------------------------------------------------------- analysis thread

    private fun onImage(img: Image) {
        val s = settings
        val now = SystemClock.uptimeMillis()
        // follow rotation: resize the SAME virtual display (a second one is forbidden on Android 14+)
        if (now - lastSizeCheck > 1000) {
            lastSizeCheck = now
            val (w, h, _) = realDisplay()
            val cap = capturer
            if (cap != null && (w != cap.width || h != cap.height)) { cap.resize(w, h); return }
        }
        if (!s.analysisOn || calibrating) return
        if (now - lastProcessed < MIN_INTERVAL_MS) return
        lastProcessed = now

        val plane = img.planes[0]
        val k = FrameConverter.scaleFor(img.width, img.height)
        val aw = img.width / k; val ah = img.height / k
        var f = frame
        if (f == null || f.width != aw || f.height != ah) {
            f = Frame(aw, ah, IntArray(aw * ah), k)
            frame = f
            mask = BooleanArray(aw * ah)
        }
        FrameConverter.fromRgba(plane.buffer, img.width, img.height, plane.rowStride, plane.pixelStride, k, f.argb)

        // tell the analyser which pixels are KENO's own drawing (it would otherwise see its own overlay)
        val m = mask!!
        val rects = extraRects.map { intArrayOf(it[0] / k, it[1] / k, it[2] / k, it[3] / k) }
        scene.rasterMask(m, aw, ah, SceneBuilder.MASK_MARGIN, rects)

        val res = analyzer.analyze(f, m, now, s)
        lastFrameW = aw; lastFrameH = ah
        val next = SceneBuilder.build(res, s)
        scene = next
        val postStatus = now - lastStatusPost > 400
        if (postStatus) lastStatusPost = now
        main.post {
            analysisView?.setScene(next, k)
            if (postStatus) panel?.setStatus(res.status + " [${res.processingMs} ms]")
        }
    }

    @Suppress("DEPRECATION")
    private fun realDisplay(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        val d = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        d.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
    }

    // ---------------------------------------------------------------- notification / lifecycle

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "KENO analiza ekrana", NotificationManager.IMPORTANCE_LOW))
        fun pi(action: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, CaptureOverlayService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("KENO aktivan")
            .setContentText("Vizuelna analiza ekrana je pokrenuta.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_edit), "Kontrole", pi(ACTION_TOGGLE_PANEL, 1)).build())
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "Zaustavi", pi(ACTION_STOP, 2)).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIF_ID, n)
    }

    override fun onDestroy() {
        running = false
        if (this::wm.isInitialized) {
            endCalibration()
            hidePanel()
            buttonView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            analysisView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        }
        buttonView = null; analysisView = null
        capturer?.stop(); capturer = null
        projection = null
        thread?.quitSafely(); thread = null
        super.onDestroy()
    }
}
