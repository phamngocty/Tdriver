package com.example.tymap.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.appcompat.widget.AppCompatImageView
import com.example.tymap.repository.NavigationRepository
import kotlin.math.max
import kotlin.math.min

class CropPreviewImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private var cropX: Int = 0
    private var cropY: Int = 0
    private var cropSize: Int = 100
    
    private var bmpWidth: Int = 480
    private var bmpHeight: Int = 800

    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0088FF")
        style = Paint.Style.FILL
    }

    private val handleRadius = 14f // Kích thước bán kính tay nắm kéo

    private enum class TouchState { NONE, DRAGGING, RESIZING }
    private var touchState = TouchState.NONE
    
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("tymap_settings", Context.MODE_PRIVATE)
    }

    fun setCropInfo(x: Int, y: Int, size: Int) {
        // Chỉ cập nhật từ bên ngoài nếu người dùng đang không tự kéo thả điều chỉnh
        if (touchState == TouchState.NONE) {
            this.cropX = x
            this.cropY = y
            this.cropSize = size
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val drawable = drawable ?: return
        bmpWidth = drawable.intrinsicWidth
        bmpHeight = drawable.intrinsicHeight
        if (bmpWidth <= 0 || bmpHeight <= 0 || cropSize <= 0) return

        val scaleX = width.toFloat() / bmpWidth
        val scaleY = height.toFloat() / bmpHeight

        val left = cropX * scaleX
        val top = cropY * scaleY
        val right = (cropX + cropSize) * scaleX
        val bottom = (cropY + cropSize) * scaleY

        // 1. Vẽ khung chữ nhật viewport màu đỏ đại diện cho màn hình ESP32
        canvas.drawRect(left, top, right, bottom, rectPaint)

        // 2. Vẽ nút tròn màu xanh dương ở góc dưới bên phải để kéo giãn kích thước (resize)
        canvas.drawCircle(right, bottom, handleRadius, handlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val drawable = drawable ?: return super.onTouchEvent(event)
        bmpWidth = drawable.intrinsicWidth
        bmpHeight = drawable.intrinsicHeight
        if (bmpWidth <= 0 || bmpHeight <= 0) return super.onTouchEvent(event)

        val scaleX = width.toFloat() / bmpWidth
        val scaleY = height.toFloat() / bmpHeight

        val x = event.x
        val y = event.y

        // Tọa độ chạm quy đổi về tọa độ bitmap gốc
        val bmpTouchX = x / scaleX
        val bmpTouchY = y / scaleY

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Tọa độ của tay nắm kéo giãn (bottom-right corner)
                val handleBmpX = cropX + cropSize
                val handleBmpY = cropY + cropSize

                // Tính khoảng cách chạm tới nút kéo giãn (quy đổi nút sang tọa độ bitmap)
                val handleViewX = handleBmpX * scaleX
                val handleViewY = handleBmpY * scaleY
                val distToHandle = kotlin.math.hypot(x - handleViewX, y - handleViewY)
                
                // Cho phép sai lệch chạm 40dp quanh nút tròn
                if (distToHandle < 40f) {
                    touchState = TouchState.RESIZING
                    parent.requestDisallowInterceptTouchEvent(true)
                } else if (bmpTouchX >= cropX && bmpTouchX <= cropX + cropSize &&
                           bmpTouchY >= cropY && bmpTouchY <= cropY + cropSize) {
                    touchState = TouchState.DRAGGING
                    dragOffsetX = bmpTouchX - cropX
                    dragOffsetY = bmpTouchY - cropY
                    parent.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (touchState == TouchState.DRAGGING) {
                    cropX = (bmpTouchX - dragOffsetX).toInt()
                    cropY = (bmpTouchY - dragOffsetY).toInt()

                    // Giới hạn trong khung ảnh
                    cropX = cropX.coerceIn(0, max(0, bmpWidth - cropSize))
                    cropY = cropY.coerceIn(0, max(0, bmpHeight - cropSize))
                    invalidate()
                    triggerInstantCropUpdate()
                } else if (touchState == TouchState.RESIZING) {
                    val newSize = max(40, min(bmpTouchX - cropX, bmpTouchY - cropY).toInt())
                    
                    // Giới hạn kích thước không vượt ngoài biên
                    cropSize = newSize.coerceIn(40, min(bmpWidth - cropX, bmpHeight - cropY))
                    invalidate()
                    triggerInstantCropUpdate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touchState != TouchState.NONE) {
                    // Lưu tọa độ chuẩn hóa tỉ lệ vào Preferences để Service tự động đọc
                    val normX = cropX.toFloat() / bmpWidth
                    val normY = cropY.toFloat() / bmpHeight
                    val normSize = cropSize.toFloat() / bmpWidth

                    prefs.edit().apply {
                        putFloat("map_tab_crop_x_norm", normX)
                        putFloat("map_tab_crop_y_norm", normY)
                        putFloat("map_tab_crop_size_norm", normSize)
                        apply()
                    }
                    touchState = TouchState.NONE
                }
            }
        }
        return true
    }

    private fun triggerInstantCropUpdate() {
        val info = NavigationRepository.mapPreviewInfo.value ?: return
        val fullBmp = info.fullMap ?: return
        try {
            if (cropSize > 0 && cropX + cropSize <= fullBmp.width && cropY + cropSize <= fullBmp.height) {
                val cropped = android.graphics.Bitmap.createBitmap(fullBmp, cropX, cropY, cropSize, cropSize)
                val scaled = android.graphics.Bitmap.createScaledBitmap(cropped, 240, 240, true)
                NavigationRepository.updateMapPreviewInfo(
                    NavigationRepository.MapPreviewInfo(
                        fullMap = fullBmp,
                        cropX = cropX,
                        cropY = cropY,
                        cropSize = cropSize,
                        croppedMap = scaled
                    )
                )
                if (cropped != scaled) cropped.recycle()
            }
        } catch (e: Exception) {
            // Tránh OOM hoặc lỗi tọa độ ngoài biên khi đang kéo thả nhanh
        }
    }
}
