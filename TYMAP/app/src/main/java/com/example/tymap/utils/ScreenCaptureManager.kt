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
    val isCapturing: Boolean get() = virtualDisplay != null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handler: Handler? = null
    private var handlerThread: HandlerThread? = null

    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0

    private var captureWidth = 0
    private var captureHeight = 0

    init {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Scale down capture resolution by 2x to reduce memory size by 4x and avoid OOM system kills
        captureWidth = screenWidth / 2
        captureHeight = screenHeight / 2
    }

    fun startCapture(projection: MediaProjection) {
        try {
            mediaProjection = projection
            handlerThread = HandlerThread("ScreenCaptureThread").apply { start() }
            handler = Handler(handlerThread!!.looper)

            imageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            // Dummy listener forces VirtualDisplay to keep rendering frames continuously on various OEMs (Oppo, Xiaomi, Samsung, etc.)
            imageReader?.setOnImageAvailableListener({ _ ->
                // No action needed here since startMapRenderingLoop actively pulls frames using acquireLatestImage
            }, handler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenCapture",
                captureWidth, captureHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, handler
            )
            android.util.Log.d("ScreenCaptureManager", "VirtualDisplay started with size: ${captureWidth}x${captureHeight}")
        } catch (e: Exception) {
            android.util.Log.e("ScreenCaptureManager", "Error starting capture: ${e.message}", e)
        }
    }

    private var lastCapturedBytes: ByteArray? = null

    fun captureAndProcess(quality: Int, prefPrefix: String = "gmaps_"): ByteArray? {
        try {
            val bitmap = captureAsBitmap(prefPrefix)
            if (bitmap != null) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                bitmap.recycle()
                val bytes = out.toByteArray()
                lastCapturedBytes = bytes
                return bytes
            }
        } catch (e: Exception) {
            android.util.Log.e("ScreenCaptureManager", "Error in captureAndProcess: ${e.message}")
        }
        // Fallback to last successfully captured bytes if current capture is null (e.g. static screen)
        return lastCapturedBytes
    }

    fun captureAsBitmap(prefPrefix: String = "gmaps_"): Bitmap? {
        val reader = imageReader ?: return null
        val image = try {
            reader.acquireLatestImage() ?: reader.acquireNextImage()
        } catch (e: Exception) {
            null
        } ?: return null
        
        try {
            val planes = image.planes
            if (planes.isEmpty()) {
                image.close()
                return null
            }
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * captureWidth

            val bitmap = Bitmap.createBitmap(
                captureWidth + rowPadding / pixelStride,
                captureHeight, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // Calculate crop coordinates relative to captured bitmap dimensions
            val prefs = context.getSharedPreferences("tymap_settings", Context.MODE_PRIVATE)
            val normX = prefs.getFloat("${prefPrefix}crop_x_norm", 0f)
            val normY = prefs.getFloat("${prefPrefix}crop_y_norm", 0f)
            val normSize = prefs.getFloat("${prefPrefix}crop_size_norm", 0.4f).coerceAtLeast(0.01f)
            
            val bmpWidth = bitmap.width
            val bmpHeight = bitmap.height

            val absX = (normX * bmpWidth).toInt()
            val absY = (normY * bmpHeight).toInt()
            val absSize = (normSize * bmpWidth).toInt().coerceAtLeast(1)

            val safeX = absX.coerceIn(0, (bmpWidth - absSize).coerceAtLeast(0))
            val safeY = absY.coerceIn(0, (bmpHeight - absSize).coerceAtLeast(0))
            
            val cropped = Bitmap.createBitmap(bitmap, safeX, safeY, absSize, absSize)
            
            // Resize to standard preview/esp size
            val scaled = Bitmap.createScaledBitmap(cropped, 240, 240, true)
            
            if (!bitmap.isRecycled) bitmap.recycle()
            if (cropped != scaled && cropped != bitmap && !cropped.isRecycled) cropped.recycle()
            
            return scaled
        } catch (e: Exception) {
            image.close()
            android.util.Log.e("ScreenCaptureManager", "Error cropping bitmap in captureAsBitmap: ${e.message}", e)
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
