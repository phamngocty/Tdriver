package com.example.tymap.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tymap.databinding.ActivityOledColorFilterBinding
import com.example.tymap.model.OledFilter
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.service.NavigationService
import com.example.tymap.utils.PrefsHelper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

class OledColorFilterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOledColorFilterBinding
    private val filters = mutableListOf<OledFilter>()
    private lateinit var adapter: OledFilterAdapter
    private var selectedColor: Int = Color.BLACK
    private val gson = Gson()
    private var sampleBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOledColorFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadFilters()
        setupUI()
        loadSampleImage(forceRefresh = false)
    }

    private fun loadSampleImage(forceRefresh: Boolean = false) {
        if (forceRefresh) {
            Toast.makeText(this, "Đang chụp ảnh bản đồ tại vị trí hiện tại...", Toast.LENGTH_SHORT).show()
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var bmp: Bitmap? = null

            // 1. Ưu tiên chụp từ NavigationService đang chạy
            val service = NavigationService.activeInstance
            if (service != null) {
                try {
                    val bytes = service.renderOsmMap(85)
                    if (bytes != null) {
                        bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("OledColorFilter", "Error rendering from NavigationService: ${e.message}")
                }
            }

            // 2. Thử lấy từ NavigationRepository.oledBaseMap
            if (bmp == null) {
                val baseMap = NavigationRepository.oledBaseMap.value
                if (baseMap != null && !baseMap.isRecycled) {
                    try {
                        bmp = baseMap.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {}
                }
            }

            // 3. Thử lấy từ NavigationRepository.mapPreviewInfo
            if (bmp == null) {
                val previewInfo = NavigationRepository.mapPreviewInfo.value
                val map = previewInfo?.croppedMap ?: previewInfo?.fullMap
                if (map != null && !map.isRecycled) {
                    try {
                        bmp = map.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (e: Exception) {}
                }
            }

            // 4. Nếu không có ảnh từ Service, tải tile bản đồ tại tọa độ GPS hiện tại
            if (bmp == null) {
                val loc = NavigationRepository.gpsLocation.value
                val lat = loc?.latitude ?: 10.7769
                val lon = loc?.longitude ?: 106.7009
                val zoom = NavigationRepository.lastMapZoom.toInt().coerceIn(12, 17)
                try {
                    val n = 1 shl zoom
                    val x = ((lon + 180.0) / 360.0 * n).toInt()
                    val latRad = Math.toRadians(lat)
                    val y = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
                    val tileUrl = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
                    
                    val client = OkHttpClient.Builder().connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS).build()
                    val request = Request.Builder().url(tileUrl).header("User-Agent", "TYMAP/1.0").build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        val bodyBytes = response.body?.bytes()
                        if (bodyBytes != null) {
                            bmp = BitmapFactory.decodeByteArray(bodyBytes, 0, bodyBytes.size)
                        }
                    }
                    response.close()
                } catch (e: Exception) {
                    android.util.Log.e("OledColorFilter", "Error downloading GPS tile: ${e.message}")
                }
            }

            // 5. Nếu có file cache sample_crop.png từ trước
            if (bmp == null && !forceRefresh) {
                val file = File(filesDir, "sample_crop.png")
                if (file.exists()) {
                    bmp = BitmapFactory.decodeFile(file.absolutePath)
                }
            }

            // 6. Fallback cuối cùng: Sinh bản đồ mẫu với các màu đường phổ biến
            if (bmp == null) {
                bmp = createFallbackMapBitmap()
            }

            // Lưu vào cache file sample_crop.png
            bmp?.let {
                try {
                    val file = File(filesDir, "sample_crop.png")
                    val out = FileOutputStream(file)
                    it.compress(Bitmap.CompressFormat.PNG, 95, out)
                    out.flush()
                    out.close()
                } catch (e: Exception) {}
            }

            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    sampleBitmap = bmp
                    binding.ivSampleImage.setImageBitmap(bmp)
                    updateLiveOledPreview()
                    if (forceRefresh) {
                        Toast.makeText(this@OledColorFilterActivity, "Đã cập nhật ảnh bản đồ vị trí hiện tại", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun createFallbackMapBitmap(): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.parseColor("#1C1C1E"))

        val paint = Paint().apply {
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
        }

        // Đường cao tốc (Cam)
        paint.color = Color.parseColor("#FF9500")
        paint.strokeWidth = 14f
        canvas.drawLine(0f, 60f, 256f, 200f, paint)

        // Đường trục chính (Vàng)
        paint.color = Color.parseColor("#FFCC00")
        paint.strokeWidth = 10f
        canvas.drawLine(40f, 0f, 220f, 256f, paint)

        // Đường nhánh (Xanh lam)
        paint.color = Color.parseColor("#5AC8FA")
        paint.strokeWidth = 6f
        canvas.drawLine(0f, 160f, 256f, 110f, paint)

        // Tuyến đường dẫn đường (Xanh dương)
        paint.color = Color.parseColor("#007AFF")
        paint.strokeWidth = 12f
        canvas.drawLine(40f, 0f, 130f, 130f, paint)
        canvas.drawLine(130f, 130f, 256f, 200f, paint)

        return bitmap
    }

    private fun setupUI() {
        adapter = OledFilterAdapter(filters) {
            saveFilters()
            updateLiveOledPreview()
        }
        binding.rvFilters.layoutManager = LinearLayoutManager(this)
        binding.rvFilters.adapter = adapter

        binding.btnRefreshMap.setOnClickListener {
            loadSampleImage(forceRefresh = true)
        }

        binding.ivSampleImage.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                sampleBitmap?.let { bitmap ->
                    val x = (event.x * bitmap.width / v.width).toInt().coerceIn(0, bitmap.width - 1)
                    val y = (event.y * bitmap.height / v.height).toInt().coerceIn(0, bitmap.height - 1)
                    
                    selectedColor = bitmap.getPixel(x, y)
                    updateCurrentColor()
                    
                    binding.vPickerPointer.visibility = View.VISIBLE
                    binding.vPickerPointer.x = event.x + v.left - (binding.vPickerPointer.width / 2)
                    binding.vPickerPointer.y = event.y + v.top - (binding.vPickerPointer.height / 2)
                }
            }
            true
        }

        binding.sliderTolerance.addOnChangeListener { _, value, _ ->
            binding.tvToleranceValue.text = value.toInt().toString()
        }

        binding.sliderDither.addOnChangeListener { _, value, _ ->
            binding.tvDitherValue.text = value.toInt().toString()
        }

        binding.btnAddFilter.setOnClickListener {
            val filter = OledFilter(
                color = selectedColor,
                tolerance = binding.sliderTolerance.value.toInt(),
                dither = binding.sliderDither.value.toInt()
            )
            filters.add(0, filter)
            adapter.notifyItemInserted(0)
            binding.rvFilters.scrollToPosition(0)
            saveFilters()
            updateLiveOledPreview()
            Toast.makeText(this, "Đã thêm bộ lọc màu", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateLiveOledPreview() {
        val src = sampleBitmap ?: return
        val activeFilters = filters.filter { it.isActive }
        val invert = PrefsHelper.getBoolean(this, "oled_filter_invert", false)
        val threshold = PrefsHelper.getInt(this, "oled_threshold", 128)

        lifecycleScope.launch(Dispatchers.Default) {
            val resized = Bitmap.createScaledBitmap(src, 128, 64, true)
            val previewBmp = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
            val pixels = Array(64) { FloatArray(128) }

            for (y in 0 until 64) {
                for (x in 0 until 128) {
                    val pixel = resized.getPixel(x, y)
                    if (activeFilters.isNotEmpty()) {
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF

                        var bestMatchValue = 0f
                        for (f in activeFilters) {
                            val fR = (f.color shr 16) and 0xFF
                            val fG = (f.color shr 8) and 0xFF
                            val fB = f.color and 0xFF

                            val distance = Math.sqrt(((r - fR) * (r - fR) + (g - fG) * (g - fG) + (b - fB) * (b - fB)).toDouble())
                            val diffPercent = (distance / 441.67) * 100.0

                            if (diffPercent <= f.tolerance) {
                                val matchValue = ((1.0 - (diffPercent / f.tolerance)) * 255).toFloat()
                                if (matchValue > bestMatchValue) {
                                    bestMatchValue = matchValue
                                }
                            }
                        }
                        pixels[y][x] = if (invert) 255f - bestMatchValue else bestMatchValue
                    } else {
                        val r = (pixel shr 16) and 0xFF
                        val g = (pixel shr 8) and 0xFF
                        val b = pixel and 0xFF
                        pixels[y][x] = (0.299 * r + 0.587 * g + 0.114 * b).toFloat()
                    }
                }
            }

            // Floyd-Steinberg dithering
            for (y in 0 until 64) {
                for (x in 0 until 128) {
                    val oldVal = pixels[y][x]
                    val newVal = if (oldVal > threshold) 255f else 0f
                    pixels[y][x] = newVal

                    val err = oldVal - newVal
                    if (x + 1 < 128) pixels[y][x + 1] += err * 7f / 16f
                    if (y + 1 < 64) {
                        if (x - 1 >= 0) pixels[y + 1][x - 1] += err * 3f / 16f
                        pixels[y + 1][x] += err * 5f / 16f
                        if (x + 1 < 128) pixels[y + 1][x + 1] += err * 1f / 16f
                    }

                    previewBmp.setPixel(x, y, if (newVal > 128f) Color.WHITE else Color.BLACK)
                }
            }
            resized.recycle()

            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) {
                    binding.ivOledPreview.setImageBitmap(previewBmp)
                }
            }
        }
    }

    private fun updateCurrentColor() {
        binding.vCurrentColor.setBackgroundColor(selectedColor)
        binding.tvCurrentColorHex.text = String.format("#%06X", (0xFFFFFF and selectedColor))
    }

    private fun loadFilters() {
        val json = PrefsHelper.getColorFilters(this)
        val type = object : TypeToken<List<OledFilter>>() {}.type
        val list = gson.fromJson<List<OledFilter>>(json, type) ?: emptyList()
        filters.clear()
        filters.addAll(list)
    }

    private fun saveFilters() {
        val json = gson.toJson(filters)
        PrefsHelper.putColorFilters(this, json)
    }
}
