package com.example.tymap.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream

object IconUtils {
    fun drawableToBitmap(drawable: Drawable): Bitmap {
        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.coerceAtLeast(1),
            drawable.intrinsicHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun bitmapToByteArray(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
    /**
     * Converts a Bitmap to 1bpp (1 bit per pixel) monochrome data.
     * Suitable for ESP32/e-ink displays.
     * @param bitmap Input bitmap (should be square, e.g. 48x48)
     * @param width Target width
     * @param height Target height
     * @return ByteArray of size (width * height / 8)
     */
    fun convertTo1bpp(bitmap: Bitmap, width: Int = 48, height: Int = 48): ByteArray {
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val buffer = ByteArray((width * height) / 8)
        var bitIndex = 0
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixel = resized.getPixel(x, y)
                // Use luminance to decide if black or white
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val alpha = Color.alpha(pixel)
                
                // If transparent, it's off (0). If visible, check luminance.
                // For nav icons (usually white or bright), luminance > 128 is "on" (1)
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
                val isPixelOn = alpha > 128 && (luminance > 100 || (r > 100 || g > 100 || b > 100))
                
                if (isPixelOn) {
                    val byteIdx = bitIndex / 8
                    val bitPos = 7 - (bitIndex % 8) // MSB first
                    buffer[byteIdx] = (buffer[byteIdx].toInt() or (1 shl bitPos)).toByte()
                }
                bitIndex++
            }
        }
        return buffer
    }

    /**
     * Converts an Android Drawable resource (VectorDrawable) directly into 1bpp monochrome 48x48 ByteArray for ESP32.
     */
    fun getVectorDrawable1bpp(context: android.content.Context, drawableResId: Int, width: Int = 48, height: Int = 48): ByteArray? {
        return try {
            val drawable = androidx.core.content.ContextCompat.getDrawable(context, drawableResId) ?: return null
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            // Ensure vector is drawn in pure white so luminance is 255
            drawable.setTint(Color.WHITE)
            drawable.setBounds(0, 0, width, height)
            drawable.draw(canvas)
            val bytes = convertTo1bpp(bitmap, width, height)
            bitmap.recycle()
            bytes
        } catch (e: Exception) {
            android.util.Log.e("IconUtils", "Failed to render vector to 1bpp", e)
            null
        }
    }
}
