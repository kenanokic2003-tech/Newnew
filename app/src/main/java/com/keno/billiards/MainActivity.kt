package com.keno.billiards

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val captureRequest = 7001
    private val notifRequest = 7002
    private lateinit var overlayStatus: TextView
    private lateinit var runStatus: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        fun text(t: String, size: Float, bold: Boolean = false, pad: Int = 0) = TextView(this).apply {
            text = t; textSize = size; if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(pad), 0, 0)
        }
        root.addView(text("KENO V2", 26f, true))
        root.addView(text("Vizuelni pomoćnik za 8 Ball Pool. Čita samo sliku ekrana (MediaProjection); ne dira igru i ne mijenja je.", 14f, pad = 6))

        overlayStatus = text("", 14f, pad = 16)
        root.addView(text("Korak 1 — prikaz preko drugih aplikacija", 15f, true, 16))
        root.addView(overlayStatus)
        root.addView(Button(this).apply {
            text = "Dozvoli prikaz preko drugih aplikacija"
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        })

        root.addView(text("Korak 2 — pokreni KENO", 15f, true, 16))
        runStatus = text("", 14f, pad = 4)
        root.addView(runStatus)
        root.addView(Button(this).apply { text = "Pokreni KENO"; setOnClickListener { startKeno() } })
        root.addView(Button(this).apply {
            text = "Zaustavi KENO"
            setOnClickListener { startService(Intent(this@MainActivity, CaptureOverlayService::class.java).setAction(CaptureOverlayService.ACTION_STOP)); refresh() }
        })
        root.addView(Button(this).apply { text = "Otvori 8 Ball Pool"; setOnClickListener { openGame() } })

        root.addView(text("Xiaomi / HyperOS (Redmi 14C)", 15f, true, 16))
        root.addView(text(
            "• Postavke → Aplikacije → KENO → Ostale dozvole: uključi „Prikaži iskačuće prozore dok radi u pozadini”.\n" +
            "• Baterija → KENO → „Bez ograničenja”, i uključi autostart.\n" +
            "• Kad Android pita za snimanje ekrana, izaberi „Cijeli ekran”.\n" +
            "• Igraj u landscape modu. KENO čita liniju ciljanja koju igra sam crta.", 13f, pad = 4))
        root.addView(Button(this).apply {
            text = "Otvori postavke aplikacije"
            setOnClickListener { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val ok = Settings.canDrawOverlays(this)
        overlayStatus.text = if (ok) "✔ Dozvoljeno" else "✘ Nije dozvoljeno"
        runStatus.text = if (CaptureOverlayService.running) "● KENO radi (K dugme je na ekranu)" else "○ KENO nije pokrenut"
    }

    private fun startKeno() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Prvo dozvoli prikaz preko drugih aplikacija.", Toast.LENGTH_LONG).show(); return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), notifRequest) // continue in the callback
            return
        }
        requestCapture()
    }

    private fun requestCapture() {
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), captureRequest)
    }

    @Deprecated("Compatibility callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == notifRequest) requestCapture() // the service also runs without the notification being visible
    }

    @Deprecated("Compatibility callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == captureRequest) {
            if (resultCode == RESULT_OK && data != null) {
                val service = Intent(this, CaptureOverlayService::class.java).apply {
                    putExtra(CaptureOverlayService.EXTRA_RESULT_CODE, resultCode)
                    putExtra(CaptureOverlayService.EXTRA_RESULT_DATA, data)
                }
                startForegroundService(service)
                if (!openGame()) moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Snimanje ekrana nije odobreno.", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Opens 8 Ball Pool if it is installed. Returns false if not. */
    private fun openGame(): Boolean {
        val i = packageManager.getLaunchIntentForPackage("com.miniclip.eightballpool")
        if (i == null) { Toast.makeText(this, "8 Ball Pool nije pronađen.", Toast.LENGTH_SHORT).show(); return false }
        startActivity(i)
        return true
    }
}
