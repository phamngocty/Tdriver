package com.example.tymap.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.example.tymap.repository.NavigationRepository
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Hiển thị toàn bộ tile grid mà ESP32 nhận được trong chế độ Rolling Map.
 *
 * - Vẽ từng tile (256×256 hoặc scale nhỏ hơn) theo vị trí lưới tương đối.
 * - Vẽ khung tròn trắng (240×240px scaled) đại diện viewport hiện tại của ESP32.
 * - Dấu chấm vàng ở tâm khung = vị trí xe.
 * - Hỗ trợ kéo (pan) và co giãn (pinch-zoom).
 */
class RollingMapCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ------- State từ NavigationRepository -------
    private var tiles: Map<String, Bitmap> = emptyMap()
    private var centerTileX: Int = 0
    private var centerTileY: Int = 0
    private var centerTileZ: Int = 0
    private var vehiclePx: Int = 128  // pixel trong tile trung tâm (0-255)
    private var vehiclePy: Int = 128

    // ------- Viewport (pan + zoom) -------
    private var scaleFactor: Float = 1f
    private var panX: Float = 0f
    private var panY: Float = 0f
    private var isFirstDraw = true

    private val TILE_SIZE = 256f  // pixel thực của mỗi tile trước khi scale

    // ------- Paints -------
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val viewportPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val vehiclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        textSize = 22f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#1A1A2E")
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val emptyTilePaint = Paint().apply {
        color = Color.parseColor("#2A2A3E")
        style = Paint.Style.FILL
    }

    // ------- Touch handling -------
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.2f, 5f)
                invalidate()
                return true
            }
        })

    // ------- Public API -------

    fun update(state: NavigationRepository.TileStreamingState) {
        tiles = state.tiles
        centerTileX = state.centerTileX
        centerTileY = state.centerTileY
        centerTileZ = state.centerTileZ
        vehiclePx = state.vehiclePxInTile
        vehiclePy = state.vehiclePyInTile

        if (isFirstDraw && centerTileX != 0 && width > 0) {
            centerViewOnVehicle()
        }
        invalidate()
    }

    /** Căn giữa view về vị trí xe */
    fun centerViewOnVehicle() {
        if (width <= 0 || height <= 0) return
        // Vị trí xe tuyệt đối trong lưới tile
        val vehicleAbsX = 0f * TILE_SIZE + vehiclePx.toFloat()  // relative to center tile = 0,0
        val vehicleAbsY = 0f * TILE_SIZE + vehiclePy.toFloat()

        panX = width / 2f - vehicleAbsX * scaleFactor
        panY = height / 2f - vehicleAbsY * scaleFactor
        isFirstDraw = false
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isFirstDraw && centerTileX != 0) {
            centerViewOnVehicle()
        } else if (isFirstDraw) {
            // Chưa có data: căn giữa view về gốc
            panX = w / 2f
            panY = h / 2f
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Nền tối
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        if (tiles.isEmpty()) {
            labelPaint.textSize = 28f
            canvas.drawText("Chưa có tile nào", width / 2f, height / 2f, labelPaint)
            labelPaint.textSize = 18f
            canvas.drawText("Bật chế độ Rolling Map để xem", width / 2f, height / 2f + 36f, labelPaint)
            return
        }

        canvas.save()
        canvas.translate(panX, panY)
        canvas.scale(scaleFactor, scaleFactor)

        // --- Vẽ từng tile ---
        // Tìm range tileX/Y để xác định grid
        val tileKeys = tiles.keys.filter { it.endsWith(":$centerTileZ") }
        val tileCoords = tileKeys.mapNotNull { key ->
            val parts = key.split(":")
            if (parts.size == 3) {
                val tx = parts[0].toIntOrNull() ?: return@mapNotNull null
                val ty = parts[1].toIntOrNull() ?: return@mapNotNull null
                tx to ty
            } else null
        }

        if (tileCoords.isEmpty()) {
            canvas.restore()
            return
        }

        val minTX = tileCoords.minOf { it.first }
        val minTY = tileCoords.minOf { it.second }

        // Vẽ tất cả tile đã có
        for ((tx, ty) in tileCoords) {
            val relX = (tx - centerTileX).toFloat()
            val relY = (ty - centerTileY).toFloat()
            val left = relX * TILE_SIZE
            val top = relY * TILE_SIZE
            val right = left + TILE_SIZE
            val bottom = top + TILE_SIZE

            val key = "$tx:$ty:$centerTileZ"
            val bmp = tiles[key]
            if (bmp != null && !bmp.isRecycled) {
                val srcRect = Rect(0, 0, bmp.width, bmp.height)
                val dstRect = RectF(left, top, right, bottom)
                canvas.drawBitmap(bmp, srcRect, dstRect, tilePaint)
            } else {
                canvas.drawRect(left, top, right, bottom, emptyTilePaint)
            }
            // Viền lưới
            canvas.drawRect(left, top, right, bottom, gridPaint)
        }

        // Vẽ các tile chưa có trong 3×3 grid quanh tile trung tâm
        for (dx in -1..1) {
            for (dy in -1..1) {
                val tx = centerTileX + dx
                val ty = centerTileY + dy
                val key = "$tx:$ty:$centerTileZ"
                if (!tiles.containsKey(key)) {
                    val left = dx.toFloat() * TILE_SIZE
                    val top = dy.toFloat() * TILE_SIZE
                    canvas.drawRect(left, top, left + TILE_SIZE, top + TILE_SIZE, emptyTilePaint)
                    canvas.drawRect(left, top, left + TILE_SIZE, top + TILE_SIZE, gridPaint)
                    labelPaint.textSize = 18f
                    canvas.drawText("...", left + TILE_SIZE / 2, top + TILE_SIZE / 2 + 6f, labelPaint)
                }
            }
        }

        // --- Khung tròn viewport ESP32 (240×240 trong không gian ESP32 = TILE_SIZE pixels) ---
        // Tâm viewport = vị trí xe trong tile trung tâm
        val vpCenterX = vehiclePx.toFloat()  // pixel trong tile trung tâm (0-255)
        val vpCenterY = vehiclePy.toFloat()
        // Bán kính = 120px trong không gian tile 256px, scale lên TILE_SIZE
        val vpRadius = (120f / 256f) * TILE_SIZE

        viewportPaint.color = Color.WHITE
        viewportPaint.strokeWidth = 3f / scaleFactor
        canvas.drawCircle(vpCenterX, vpCenterY, vpRadius, viewportPaint)

        // Viền ngoài màu đỏ mỏng cho dễ nhìn
        viewportPaint.color = Color.parseColor("#FF4444")
        viewportPaint.strokeWidth = 1.5f / scaleFactor
        canvas.drawCircle(vpCenterX, vpCenterY, vpRadius + 2f / scaleFactor, viewportPaint)

        // Dấu chấm vàng = vị trí xe
        val dotRadius = max(4f, 6f / scaleFactor)
        canvas.drawCircle(vpCenterX, vpCenterY, dotRadius, vehiclePaint)

        // Crosshair nhỏ
        val ch = dotRadius * 2
        vehiclePaint.color = Color.YELLOW
        canvas.drawLine(vpCenterX - ch, vpCenterY, vpCenterX + ch, vpCenterY, vehiclePaint.apply { style = Paint.Style.STROKE; strokeWidth = 1.5f / scaleFactor })
        canvas.drawLine(vpCenterX, vpCenterY - ch, vpCenterX, vpCenterY + ch, vehiclePaint)
        vehiclePaint.style = Paint.Style.FILL

        canvas.restore()

        // Label overlay (không bị scale/pan)
        labelPaint.textSize = 20f
        val statusText = if (centerTileZ > 0)
            "Tile: ($centerTileX, $centerTileY) Z=$centerTileZ  |  ${tiles.size} tiles"
        else "Chờ vị trí GPS..."
        canvas.drawText(statusText, width / 2f, 28f, labelPaint)
    }

    // ------- Touch: pan + pinch zoom -------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                activePointerId = event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Pinch start: không xử lý pan
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                    if (pointerIndex >= 0) {
                        panX += event.getX(pointerIndex) - lastTouchX
                        panY += event.getY(pointerIndex) - lastTouchY
                        lastTouchX = event.getX(pointerIndex)
                        lastTouchY = event.getY(pointerIndex)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activePointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    lastTouchX = event.getX(newIndex)
                    lastTouchY = event.getY(newIndex)
                    activePointerId = event.getPointerId(newIndex)
                }
            }
        }
        return true
    }
}
