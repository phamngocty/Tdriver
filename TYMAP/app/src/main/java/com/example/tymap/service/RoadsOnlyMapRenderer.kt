package com.example.tymap.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import com.example.tymap.repository.NavigationRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.ByteArrayOutputStream

class RoadsOnlyMapRenderer(private val context: Context) {

    private val darkNoLabelsTileSource = XYTileSource(
        "DarkNoLabels",
        1, 20, 256, ".png",
        arrayOf(
            "https://a.basemaps.cartocdn.com/rastertiles/dark_nolabels/",
            "https://b.basemaps.cartocdn.com/rastertiles/dark_nolabels/",
            "https://c.basemaps.cartocdn.com/rastertiles/dark_nolabels/"
        ),
        "© OpenStreetMap contributors, © CARTO"
    )

    suspend fun render(
        headlessMapView: MapView?,
        location: Location?,
        heading: Float,
        zoom: Double,
        quality: Int
    ): ByteArray? {
        if (headlessMapView == null) return null
        
        val renderSize = 240
        val bitmap = Bitmap.createBitmap(renderSize, renderSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        withContext(Dispatchers.Main) {
            // 1. Cấu hình TileSource Dark No Labels
            if (headlessMapView.tileProvider?.tileSource != darkNoLabelsTileSource) {
                headlessMapView.setTileSource(darkNoLabelsTileSource)
            }
            
            // 2. Xóa các overlays cũ
            headlessMapView.overlays?.clear()
            
            // 3. Vẽ polyline tuyến đường (màu xanh dương #0066FF, dày 4px)
            val routes = NavigationRepository.routes.value
            if (routes.isNotEmpty()) {
                val tolerance = 3.0 * (360.0 / (256.0 * Math.pow(2.0, zoom)))
                val unselected = routes.filter { !it.isSelected }
                val selected = routes.filter { it.isSelected }
                
                (unselected + selected).forEach { route ->
                    val simplifiedPoints = com.example.tymap.utils.PolylineDecoder.simplify(route.polyline, tolerance)
                    if (simplifiedPoints.size >= 2) {
                        val polyline = Polyline(headlessMapView).apply {
                            setPoints(simplifiedPoints.map { org.osmdroid.util.GeoPoint(it.first, it.second) })
                            outlinePaint.isAntiAlias = true
                            outlinePaint.color = if (route.isSelected) Color.parseColor("#0066FF") else Color.parseColor("#444444")
                            outlinePaint.strokeWidth = if (route.isSelected) 4f else 2f
                            outlinePaint.strokeCap = Paint.Cap.ROUND
                            outlinePaint.strokeJoin = Paint.Join.ROUND
                        }
                        headlessMapView.overlays?.add(polyline)
                    }
                }
            }
            
            // 4. Cấu hình tâm bản đồ, góc xoay và zoom
            location?.let {
                headlessMapView.controller?.setCenter(org.osmdroid.util.GeoPoint(it.latitude, it.longitude))
                headlessMapView.mapOrientation = -heading
            }
            headlessMapView.controller?.setZoom(zoom)
            
            // 5. Layout và vẽ MapView lên canvas của bitmap
            headlessMapView.layout(0, 0, renderSize, renderSize)
            headlessMapView.draw(canvas)
        }
        
        // 6. Xử lý ảnh Bitmap: Lọc pixel đường đi và làm trong suốt nền
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        
        val threshold = 25
        for (i in pixels.indices) {
            val color = pixels[i]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            
            // Bỏ qua các pixel thuộc polyline đã vẽ (màu xanh dương có kênh Blue trội hơn Red và Green)
            if (b > r * 1.25f && b > g * 1.25f && b > 60) {
                continue
            }
            
            if (r > threshold || g > threshold || b > threshold) {
                // Đây là đường đi -> Đổi sang màu xám/trắng nhạt
                pixels[i] = Color.argb(255, 220, 220, 220)
            } else {
                // Nền tối hoặc nước/nhà cửa -> Làm trong suốt hoàn toàn
                pixels[i] = Color.TRANSPARENT
            }
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        
        // 7. Vẽ marker vị trí xe (chấm xanh lá #00FF00, bán kính 6px) ở tâm màn hình (120, 120)
        val finalCanvas = Canvas(bitmap)
        val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00FF00")
            style = Paint.Style.FILL
        }
        finalCanvas.drawCircle(120f, 120f, 6f, markerPaint)
        
        val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        finalCanvas.drawCircle(120f, 120f, 6f, markerStrokePaint)
        
        try {
            val copy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            com.example.tymap.repository.NavigationRepository.updateMapPreviewInfo(
                com.example.tymap.repository.NavigationRepository.MapPreviewInfo(
                    fullMap = copy,
                    cropX = 0,
                    cropY = 0,
                    cropSize = width,
                    croppedMap = copy
                )
            )
        } catch (e: Exception) {
            android.util.Log.e("RoadsOnlyMapRenderer", "Error copying preview maps: ${e.message}")
        }

        // 8. Nén ảnh JPEG (nền trong suốt sẽ tự động nén thành màu đen)
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val bytes = outputStream.toByteArray()
            if (!bitmap.isRecycled) bitmap.recycle()
            bytes
        } catch (e: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            null
        }
    }
}
