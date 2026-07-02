package com.example.tymap.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

class ScreenCaptureManager(private val context: Context) {
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi
    }

    fun startCapture(projection: MediaProjection) {
        mediaProjection = projection
        handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
        handler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCapture",
            screenWidth, screenHeight, screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
    }

    fun captureAndProcess(quality: Int, prefPrefix: String = "gmaps_"): ByteArray? {
        val bitmap = captureAsBitmap(prefPrefix) ?: return null
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    fun captureAsBitmap(prefPrefix: String = "gmaps_"): Bitmap? {
        val image = imageReader?.acquireLatestImage() ?: return null
        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Calculate crop coordinates
            val prefs = context.getSharedPreferences("tymap_settings", Context.MODE_PRIVATE)
            val normX = prefs.getFloat("${prefPrefix}crop_x_norm", 0f)
            val normY = prefs.getFloat("${prefPrefix}crop_y_norm", 0f)
            val normSize = prefs.getFloat("${prefPrefix}crop_size_norm", 0.4f)
            
            val absX = (normX * screenWidth).toInt()
            val absY = (normY * screenHeight).toInt()
            val absSize = (normSize * screenWidth).toInt()

            val safeX = absX.coerceIn(0, (bitmap.width - absSize).coerceAtLeast(0))
            val safeY = absY.coerceIn(0, (bitmap.height - absSize).coerceAtLeast(0))
            
            val cropped = Bitmap.createBitmap(bitmap, safeX, safeY, absSize, absSize)
            
            // Resize to standard preview/esp size
            val scaled = Bitmap.createScaledBitmap(cropped, 240, 240, true)
            
            bitmap.recycle()
            if (cropped != scaled) cropped.recycle()
            
            return scaled
        } catch (e: Exception) {
            image.close()
            return null
        }
    }

    fun stopCapture() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        handlerThread?.quitSafely()
        
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }
}
