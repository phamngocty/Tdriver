package com.example.tymap.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import java.io.ByteArrayOutputStream

object IconUtils {
    fun drawableToBitmap(drawable: Drawable, width: Int = 48, height: Int = 48): Bitmap {
        val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else width
        val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else height
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
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
     * Kiểm tra xem mảng byte 1bpp có hợp lệ không (loại bỏ bitmap rỗng hoàn toàn hoặc bị bệt trắng đặc > 85%).
     */
    fun is1bppEmpty(buffer: ByteArray?): Boolean {
        if (buffer == null || buffer.isEmpty()) return true
        var onBits = 0
        for (b in buffer) {
            if (b != 0.toByte()) {
                onBits += java.lang.Integer.bitCount(b.toInt() and 0xFF)
            }
        }
        val totalBits = buffer.size * 8
        // Hợp lệ cho icon 48x48: có ít nhất 10 pixel và không quá 85% pixel bị bật (tránh khối trắng đặc)
        return onBits < 10 || onBits > (totalBits * 0.85f)
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
        val resized = if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }
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
                // For nav icons (usually white or bright), luminance > 100 is "on" (1)
                // Hỗ trợ cả silhouette đen trên nền trong suốt (alpha > 180, dark pixel)
                val luminance = (0.299 * r + 0.587 * g + 0.114 * b)
                val isPixelOn = (alpha > 80 && luminance > 90) || (alpha > 180 && luminance < 50 && (r == 0 && g == 0 && b == 0))
                
                if (isPixelOn) {
                    val byteIdx = bitIndex / 8
                    val bitPos = 7 - (bitIndex % 8) // MSB first
                    buffer[byteIdx] = (buffer[byteIdx].toInt() or (1 shl bitPos)).toByte()
                }
                bitIndex++
            }
        }
        if (resized != bitmap) {
            resized.recycle()
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
