package com.example.tymap.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.example.tymap.R
import com.example.tymap.utils.PrefsHelper

class CropOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var cropFrame: View
    private var scaleFactor = 1.0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var cropType: String = "gmaps" // "gmaps" or "map_tab"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        cropType = intent?.getStringExtra("CROP_TYPE") ?: "gmaps"
        
        // Update guide text if already visible
        if (::overlayView.isInitialized) {
            val tvGuide = overlayView.findViewById<TextView>(R.id.tvGuide)
            tvGuide?.text = if (cropType == "gmaps") "Cấu hình cắt Google Maps" else "Cấu hình cắt Tab Map"
            
            if (cropType == "gmaps") {
                val launchIntent = packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            } else {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        }
        
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        overlayView = LayoutInflater.from(this).inflate(R.layout.activity_crop_config, null)
        cropFrame = overlayView.findViewById(R.id.cropFrame)
        val btnSave = overlayView.findViewById<Button>(R.id.btnSaveCrop)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )

        setupDragAndScaleLogic()

        btnSave.setOnClickListener {
            saveConfiguration()
            stopSelf()
        }

        windowManager.addView(overlayView, params)
    }

    private fun setupDragAndScaleLogic() {
        var dX = 0f
        var dY = 0f

        // Initial size based on saved config
        val initialSizeNorm = if (cropType == "gmaps") 
            PrefsHelper.getFloat(this, "gmaps_crop_size_norm", 0.4f)
        else
            PrefsHelper.getFloat(this, "map_tab_crop_size_norm", 0.4f)
        
        scaleFactor = initialSizeNorm / 0.4f // normalized to initial 200dp logic

        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor *= detector.scaleFactor
                scaleFactor = scaleFactor.coerceIn(0.2f, 5.0f)
                
                val newSize = (200 * resources.displayMetrics.density * scaleFactor).toInt()
                val lp = cropFrame.layoutParams
                lp.width = newSize
                lp.height = newSize
                cropFrame.layoutParams = lp
                return true
            }
        })

        overlayView.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            
            if (event.pointerCount == 1) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        dX = cropFrame.x - event.rawX
                        dY = cropFrame.y - event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        cropFrame.x = event.rawX + dX
                        cropFrame.y = event.rawY + dY
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

        val absX = cropFrame.x
        val absY = cropFrame.y
        val absSize = cropFrame.width

        val prefix = if (cropType == "gmaps") "gmaps_" else "map_tab_"

        PrefsHelper.putInt(this, "${prefix}crop_x", absX.toInt())
        PrefsHelper.putInt(this, "${prefix}crop_y", absY.toInt())
        PrefsHelper.putInt(this, "${prefix}crop_size", absSize)

        PrefsHelper.putFloat(this, "${prefix}crop_x_norm", absX / screenWidth)
        PrefsHelper.putFloat(this, "${prefix}crop_y_norm", absY / screenHeight)
        PrefsHelper.putFloat(this, "${prefix}crop_size_norm", absSize.toFloat() / screenWidth)
        
        // Take sample screenshot for OLED Color Filter Settings
        NavigationService.screenCaptureManager?.captureAsBitmap(prefix)?.let { bitmap ->
            try {
                val file = java.io.File(filesDir, "sample_crop.png")
                val out = java.io.FileOutputStream(file)
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                out.close()
                bitmap.recycle()
            } catch (e: Exception) { e.printStackTrace() }
        }

        Toast.makeText(this, "Đã lưu vùng cắt $cropType", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}
