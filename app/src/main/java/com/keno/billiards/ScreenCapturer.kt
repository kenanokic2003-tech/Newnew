package com.keno.billiards

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.graphics.PixelFormat
import android.os.Handler

/**
 * Owns the ImageReader + VirtualDisplay for one MediaProjection.
 *
 * Order matters on Android 14+: the MediaProjection.Callback must be registered BEFORE the first
 * createVirtualDisplay(), and a MediaProjection may only create ONE VirtualDisplay. Rotation is therefore
 * handled by resize() + setSurface() on the same VirtualDisplay, never by creating a second one.
 */
class ScreenCapturer(
    private val projection: MediaProjection,
    private val handler: Handler,
    private val dpi: Int,
    private val onStopped: () -> Unit,
    private val onImage: (Image) -> Unit,
) {
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    var width = 0
        private set
    var height = 0
        private set

    private val callback = object : android.media.projection.MediaProjection.Callback() {
        override fun onStop() { onStopped() }
    }

    fun start(w: Int, h: Int) {
        projection.registerCallback(callback, handler) // must come first
        width = w; height = h
        val r = newReader(w, h)
        reader = r
        display = projection.createVirtualDisplay(
            "KENO-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler
        )
    }

    fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        val vd = display ?: return
        width = w; height = h
        val old = reader
        val r = newReader(w, h)
        reader = r
        vd.resize(w, h, dpi)
        vd.surface = r.surface
        old?.setOnImageAvailableListener(null, null)
        old?.close()
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir ->
            val img = try { ir.acquireLatestImage() } catch (e: Exception) { null }
            if (img != null) {
                try { onImage(img) } finally { img.close() }
            }
        }, handler)
        return r
    }

    fun stop() {
        try { projection.unregisterCallback(callback) } catch (_: Exception) {}
        display?.release(); display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close(); reader = null
        try { projection.stop() } catch (_: Exception) {}
    }
}
