package com.example.tymap.ui

import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.tymap.databinding.ActivityCropConfigBinding
import com.example.tymap.utils.PrefsHelper

class CropConfigActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCropConfigBinding
    private var scaleFactor = 1.0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCropConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDragAndScaleLogic()

        binding.btnSaveCrop.setOnClickListener {
            saveConfiguration()
        }
    }

    private fun setupDragAndScaleLogic() {
        var dX = 0f
        var dY = 0f

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.5f, 3.0f)
                
                binding.cropFrame.layoutParams.width = (200 * resources.displayMetrics.density * scaleFactor).toInt()
                binding.cropFrame.layoutParams.height = binding.cropFrame.layoutParams.width
                binding.cropFrame.requestLayout()
                return true
            }
        })

        binding.root.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            
            if (event.pointerCount == 1) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = binding.cropFrame.x - event.rawX
                        dY = binding.cropFrame.y - event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        binding.cropFrame.animate()
                            .x(event.rawX + dX)
                            .y(event.rawY + dY)
                            .setDuration(0)
                            .start()
                    }
                }
            }
            true
        }
    }

    private fun saveConfiguration() {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()

        // Tọa độ tuyệt đối trên màn hình
        val absX = binding.cropFrame.x
        val absY = binding.cropFrame.y
        val absSize = binding.cropFrame.width

        // Lưu cả tọa độ tuyệt đối để dùng trong ScreenCaptureManager
        PrefsHelper.putInt(this, "crop_x", absX.toInt())
        PrefsHelper.putInt(this, "crop_y", absY.toInt())
        PrefsHelper.putInt(this, "crop_size", absSize)

        // Lưu tọa độ chuẩn hóa (0.0 -> 1.0) để dự phòng
        val normX = absX / screenWidth
        val normY = absY / screenHeight
        val normSize = absSize / screenWidth
        PrefsHelper.putCropConfig(this, normX, normY, normSize)
        
        Toast.makeText(this, "Đã lưu vùng cắt", Toast.LENGTH_SHORT).show()
        finish()
    }
}
