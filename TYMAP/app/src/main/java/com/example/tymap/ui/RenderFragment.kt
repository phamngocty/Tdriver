package com.example.tymap.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.R
import com.example.tymap.databinding.FragmentRenderBinding
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.service.NavigationService
import com.example.tymap.utils.IconUtils
import com.example.tymap.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RenderFragment : Fragment() {
    private var _binding: FragmentRenderBinding? = null
    private val binding get() = _binding!!
    private lateinit var logAdapter: LogAdapter
    private val logList = mutableListOf<String>()
    private var isLogPaused = false
    private var searchQuery = ""

    enum class SimulatorMode { AUTO, WATCH_ROUND, OLED_2TO1 }
    private var currentSimMode = SimulatorMode.AUTO
    private var detectedIsOled = false
    private var userManuallySelectedSim = false
    private var oledSimTabMode = 0 // 0: Status, 1: HUD, 2: Map
    private var oledMapSubStyle = 0 // 0: M1 Thuần Map 128x64, 1: Chỉ có Map (Pure), 2: M2 Chia đôi HUD

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRenderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, insets.top, v.paddingRight, v.paddingBottom)
            windowInsets
        }
        setupLogRecyclerView()
        setupSimulatorToggle()

        // 0. Observe OLED connection vs Watch round display (Tỷ lệ 2:1 khi kết nối OLED)
        lifecycleScope.launch {
            combine(
                NavigationRepository.bleConnectionState,
                NavigationRepository.deviceStatus,
                NavigationRepository.lastSentMapImage
            ) { state, status, lastImage ->
                val connected = state == NavigationRepository.BleConnectionState.Connected ||
                    state == NavigationRepository.BleConnectionState.Ready
                val display = status["display"] ?: PrefsHelper.getString(requireContext(), "connected_device_display", "GC9A01")
                val isOledFromDevice = connected && (display.contains("OLED", ignoreCase = true) ||
                    display.contains("SSD1306", ignoreCase = true) ||
                    display.contains("SH1106", ignoreCase = true) ||
                    display.contains("TYMAP", ignoreCase = true))
                val isOledFromImage = lastImage != null && lastImage.width == 128 && lastImage.height == 64
                
                val isOled = isOledFromDevice || isOledFromImage
                Triple(isOled, connected, status)
            }.collectLatest { (isOled, connected, status) ->
                val wasOled = detectedIsOled
                detectedIsOled = isOled
                
                if (!userManuallySelectedSim) {
                    val desiredMode = if (isOled) SimulatorMode.OLED_2TO1 else SimulatorMode.WATCH_ROUND
                    if (currentSimMode != desiredMode) {
                        currentSimMode = desiredMode
                        val targetBtn = if (isOled) R.id.btnSimOled else R.id.btnSimWatch
                        if (binding.toggleSimulatorMode.checkedButtonId != targetBtn) {
                            binding.toggleSimulatorMode.check(targetBtn)
                        }
                        updateSimulatorVisibility()
                    }
                }
                
                if (isOled) {
                    val displayType = status["display"]?.takeIf { it.isNotBlank() } ?: "SH1106 / SSD1306"
                    val mode = status["mode"]?.takeIf { it.isNotBlank() } ?: "MAP"
                    val voltage = status["voltage"]
                    val voltageStr = if (!voltage.isNullOrEmpty()) " • ${voltage}V" else ""
                    binding.tvOledCardTitle.text = "Màn hình OLED 128x64 ($displayType)"
                    binding.tvOledStatusDetail.text = "Trạng thái: $mode$voltageStr"
                }
            }
        }

        // 1. Observe Base Map (Bản đồ gốc khi chạy chế độ OLED)
        lifecycleScope.launch {
            NavigationRepository.oledBaseMap.collectLatest { baseBmp ->
                if (baseBmp != null && !baseBmp.isRecycled && currentSimMode == SimulatorMode.OLED_2TO1) {
                    binding.ivOledPreview.setImageBitmap(baseBmp)
                    refreshOledSimulator()
                }
            }
        }

        // 2. Observe mapPreviewInfo (rolling crop simulation cho màn tròn S3)
        lifecycleScope.launch {
            NavigationRepository.mapPreviewInfo.collectLatest { info ->
                if (info != null) {
                    if (currentSimMode == SimulatorMode.WATCH_ROUND) {
                        if (info.croppedMap != null && !info.croppedMap.isRecycled) {
                            binding.ivMapPreview.scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            binding.ivMapPreview.setImageBitmap(info.croppedMap)
                        }
                        if (info.fullMap != null && !info.fullMap.isRecycled) {
                            binding.ivFullMapPreview.setImageBitmap(info.fullMap)
                            binding.ivFullMapPreview.setCropInfo(info.cropX, info.cropY, info.cropSize)
                        }
                    } else if (currentSimMode == SimulatorMode.OLED_2TO1) {
                        if (info.croppedMap != null && !info.croppedMap.isRecycled && NavigationRepository.oledBaseMap.value == null) {
                            binding.ivOledPreview.setImageBitmap(info.croppedMap)
                            refreshOledSimulator()
                        }
                    }
                }
            }
        }

        // 3. Observe last sent map image (OLED 128x64 display preview - Tỷ lệ 2:1 hoặc Watch)
        lifecycleScope.launch {
            NavigationRepository.lastSentMapImage.collectLatest { bitmap ->
                if (bitmap != null && !bitmap.isRecycled) {
                    val isOled = bitmap.width == 128 && bitmap.height == 64
                    if (isOled && currentSimMode == SimulatorMode.OLED_2TO1) {
                        binding.ivOledPreview.setImageBitmap(bitmap)
                        refreshOledSimulator()
                    } else if (!isOled && currentSimMode == SimulatorMode.WATCH_ROUND) {
                        binding.ivMapPreview.setImageBitmap(bitmap)
                    }
                }
            }
        }

        // 4. Observe navigation HUD preview data (for turn icon and OLED HUD)
        lifecycleScope.launch {
            NavigationRepository.hudPreviewData.collectLatest { hud ->
                if (hud != null) {
                    if (hud.bitmapIcon != null) {
                        binding.ivIconPreview.setImageBitmap(hud.bitmapIcon)
                        binding.ivIconPreview.visibility = View.VISIBLE
                    } else if (hud.iconIndex >= 0) {
                        val iconRes = maneuverIconRes(hud.iconIndex)
                        binding.ivIconPreview.setImageResource(iconRes)
                        binding.ivIconPreview.visibility = View.VISIBLE
                    } else {
                        binding.ivIconPreview.visibility = View.GONE
                    }
                } else {
                    binding.ivIconPreview.visibility = View.GONE
                }
                if (currentSimMode == SimulatorMode.OLED_2TO1) {
                    refreshOledSimulator()
                }
            }
        }

        // 5. Observe GPS speed and map mode to refresh OLED simulator
        lifecycleScope.launch {
            combine(
                NavigationRepository.gpsLocation,
                NavigationRepository.mapModeState
            ) { _, _ -> Unit }.collectLatest {
                if (currentSimMode == SimulatorMode.OLED_2TO1) {
                    refreshOledSimulator()
                }
            }
        }

        // 6. Observe currently prepared/sending BLE packet
        lifecycleScope.launch {
            NavigationRepository.preparedBleData.collectLatest { data ->
                if (data.isNotEmpty()) {
                    binding.tvBlePacketData.text = data
                } else {
                    binding.tvBlePacketData.text = "Chưa có gói dữ liệu nào được kết xuất..."
                }
            }
        }

        // 7. Observe system BLE logs (không dùng scrollToPosition để tránh giật ScrollView)
        lifecycleScope.launch {
            NavigationRepository.logs.collectLatest { logs ->
                if (!isLogPaused) {
                    val filtered = if (searchQuery.isEmpty()) {
                        logs
                    } else {
                        logs.filter { it.contains(searchQuery, ignoreCase = true) }
                    }
                    if (logList != filtered) {
                        logList.clear()
                        logList.addAll(filtered)
                        logAdapter.notifyDataSetChanged()
                    }
                }
            }
        }
 
        // 5. Setup Action Buttons
        binding.btnSendCurrentMap.setOnClickListener {
            sendCurrentMapImage()
        }
 
        binding.btnSendDemoNav.setOnClickListener {
            sendDemoNavigationData()
        }
 
        binding.btnClearRenderLogs.setOnClickListener {
            NavigationRepository.clearLogs()
            Toast.makeText(requireContext(), "Đã xóa lịch sử log", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearRenderLogsTab.setOnClickListener {
            NavigationRepository.clearLogs()
            Toast.makeText(requireContext(), "Đã xóa lịch sử log", Toast.LENGTH_SHORT).show()
        }

        binding.btnPauseRenderLog.setOnClickListener {
            isLogPaused = !isLogPaused
            binding.btnPauseRenderLog.text = if (isLogPaused) "Tiếp tục cuộn" else "Tạm dừng cuộn"
            binding.btnPauseRenderLog.setIconResource(if (isLogPaused) R.drawable.ic_play else R.drawable.ic_stop)
            if (!isLogPaused) {
                val logs = NavigationRepository.logs.value
                logList.clear()
                val filtered = if (searchQuery.isEmpty()) {
                    logs
                } else {
                    logs.filter { it.contains(searchQuery, ignoreCase = true) }
                }
                logList.addAll(filtered)
                logAdapter.notifyDataSetChanged()
                if (logList.isNotEmpty()) {
                    binding.rvRenderLogs.scrollToPosition(0)
                }
            }
        }

        binding.etSearchRenderLog.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                val logs = NavigationRepository.logs.value
                logList.clear()
                val filtered = if (searchQuery.isEmpty()) {
                    logs
                } else {
                    logs.filter { it.contains(searchQuery, ignoreCase = true) }
                }
                logList.addAll(filtered)
                logAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        // Tự động kích hoạt kết xuất bản đồ giả lập ngay khi mở tab Render
        triggerAutoRenderPreview()
    }

    override fun onResume() {
        super.onResume()
        triggerAutoRenderPreview()
    }

    private fun setupSimulatorToggle() {
        val initialBtn = R.id.btnSimOled
        binding.toggleSimulatorMode.check(initialBtn)
        currentSimMode = SimulatorMode.OLED_2TO1
        updateSimulatorVisibility()

        binding.toggleSimulatorMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                userManuallySelectedSim = true
                currentSimMode = if (checkedId == R.id.btnSimOled) {
                    SimulatorMode.OLED_2TO1
                } else {
                    SimulatorMode.WATCH_ROUND
                }
                updateSimulatorVisibility()
            }
        }

        binding.toggleOledMode.check(R.id.btnOledStatus)
        binding.toggleOledMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnOledStatus -> {
                        oledSimTabMode = 0
                        binding.tvOledModeDesc.text = "Đồng hồ Status: Giả lập màn hình chờ xe máy với đồng hồ số lớn, điện áp ắc quy, thanh đo tốc độ KM/H."
                        binding.toggleOledMapStyle.visibility = View.GONE
                    }
                    R.id.btnOledHud -> {
                        oledSimTabMode = 1
                        binding.tvOledModeDesc.text = "Chỉ dẫn HUD: Giả lập màn hình Turn-by-Turn với icon rẽ lớn, cự ly ngã rẽ và tên đường."
                        binding.toggleOledMapStyle.visibility = View.GONE
                    }
                    R.id.btnOledMap -> {
                        oledSimTabMode = 2
                        binding.toggleOledMapStyle.visibility = View.VISIBLE
                        updateOledMapDesc()
                    }
                }
                refreshOledSimulator()
            }
        }

        // Đọc cấu hình kiểu Map OLED đã lưu trong Settings
        val savedMapStyle = PrefsHelper.getInt(requireContext(), "oled_map_style", 0)
        oledMapSubStyle = savedMapStyle
        when (savedMapStyle) {
            0 -> binding.toggleOledMapStyle.check(R.id.btnOledMapM1)
            1 -> binding.toggleOledMapStyle.check(R.id.btnOledMapPure)
            else -> binding.toggleOledMapStyle.check(R.id.btnOledMapM2)
        }

        binding.toggleOledMapStyle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnOledMapM1 -> oledMapSubStyle = 0
                    R.id.btnOledMapPure -> oledMapSubStyle = 1
                    R.id.btnOledMapM2 -> oledMapSubStyle = 2
                }
                PrefsHelper.putInt(requireContext(), "oled_map_style", oledMapSubStyle)
                com.example.tymap.service.NavigationService.bleManager?.writeSettings("{\"oled_map_style\":$oledMapSubStyle}")
                updateOledMapDesc()
                refreshOledSimulator()
            }
        }
    }

    private fun updateOledMapDesc() {
        when (oledMapSubStyle) {
            0 -> binding.tvOledModeDesc.text = "M1 Thuần Map Toàn Màn Hình: 128x64 toàn diện tích, tích hợp Mini HUD (150M➔), thước đo 50m và tốc độ xe."
            1 -> binding.tvOledModeDesc.text = "Chỉ có MAP không (Pure Map 100%): 100% diện tích cho lộ trình & đường xá, không che khuất bởi chữ hay khung HUD."
            else -> binding.tvOledModeDesc.text = "M2 Map Chia Đôi Kèm HUD: Bên trái là bản đồ 88x64, vách ngăn x=88, bên phải là cụm chỉ dẫn Turn-by-Turn."
        }
    }

    private fun updateSimulatorVisibility() {
        val showOled = when (currentSimMode) {
            SimulatorMode.OLED_2TO1 -> true
            SimulatorMode.WATCH_ROUND -> false
            SimulatorMode.AUTO -> detectedIsOled
        }

        // layoutWatchDisplayRow luôn hiển thị dạng 2 view song song (Trái: Thiết bị hiển thị, Phải: Bản đồ nguồn)
        binding.layoutWatchDisplayRow.visibility = View.VISIBLE

        if (showOled) {
            // Cột bên trái: Màn hình OLED 128x64 (ESP32-C3)
            binding.tvLeftMapTitle.text = "Màn hình OLED 128x64 (ESP32-C3)"
            binding.layoutLeftMapFrame.visibility = View.GONE
            binding.layoutLeftOledFrame.visibility = View.VISIBLE
            binding.tvOledStatusDetail.visibility = View.VISIBLE

            // Cột bên phải: Nguồn thu nhận / Bản đồ gốc (Base Map)
            binding.tvRollingMapTitle.text = "Bản đồ gốc (Base Map)"
            binding.ivFullMapPreview.visibility = View.GONE
            binding.layoutOledFrame.visibility = View.VISIBLE
            binding.tvRollingMapDesc.text = "Nguồn thu nhận / Bản đồ gốc"

            // Card điều khiển các chế độ mô phỏng OLED (Status, HUD, Map)
            binding.cardOledContainer.visibility = View.VISIBLE

            refreshOledSimulator()
        } else {
            // Cột bên trái: Màn hình Watch tròn 240x240 (GC9A01 / ESP32-S3)
            binding.tvLeftMapTitle.text = "Màn hình Watch (240x240)"
            binding.layoutLeftMapFrame.visibility = View.VISIBLE
            binding.layoutLeftOledFrame.visibility = View.GONE
            binding.tvOledStatusDetail.visibility = View.GONE

            // Cột bên phải: Bản đồ Cuốn chiếu (Rolling Viewport)
            binding.tvRollingMapTitle.text = "Bản đồ Cuốn chiếu (Rolling)"
            binding.ivFullMapPreview.visibility = View.VISIBLE
            binding.layoutOledFrame.visibility = View.GONE
            binding.tvRollingMapDesc.text = "Bản đồ nguồn 240x400 (cuộn dọc)"

            // Ẩn thanh chọn chế độ OLED
            binding.cardOledContainer.visibility = View.GONE
        }
    }

    private fun triggerAutoRenderPreview() {
        refreshOledSimulator()
        lifecycleScope.launch(Dispatchers.IO) {
            val service = NavigationService.activeInstance
            if (service != null) {
                try {
                    val quality = PrefsHelper.getFloat(requireContext(), "jpeg_quality", 70f).toInt()
                    val jpeg = service.renderOsmMap(quality)
                    if (jpeg != null) {
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        if (bmp != null) {
                            NavigationRepository.updateOledBaseMap(bmp)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("RenderFragment", "Error auto-rendering preview: ${e.message}")
                }
            } else {
                try {
                    val intent = android.content.Intent(requireContext(), NavigationService::class.java)
                    requireContext().startForegroundService(intent)
                } catch (e: Exception) {}
            }
        }
    }

    /**
     * Tự động làm mới và kết xuất giả lập màn hình OLED 128x64 chuẩn:
     * 1. Bản đồ (Map mode): render bitmap đen trắng / cyan 128x64
     * 2. Dẫn đường (HUD mode): render icon rẽ lớn, khoảng cách, tên đường, ETA, tốc độ
     * 3. Màn hình chờ (Status mode): render đồng hồ lớn, tốc độ GPS, điện áp bình, thanh trạng thái
     */
    private fun refreshOledSimulator() {
        if (_binding == null) return
        try {
            val isMapActive = NavigationRepository.mapModeState.value
            val lastImg = NavigationRepository.lastSentMapImage.value
            val oledBase = NavigationRepository.oledBaseMap.value
            val previewCrop = NavigationRepository.mapPreviewInfo.value?.croppedMap
            val hud = NavigationRepository.hudPreviewData.value

            val validLastImg = if (lastImg != null && !lastImg.isRecycled) lastImg else null
            val validOledBase = if (oledBase != null && !oledBase.isRecycled) oledBase else null
            val validPreviewCrop = if (previewCrop != null && !previewCrop.isRecycled) previewCrop else null

            val bmp: Bitmap = when (oledSimTabMode) {
                1 -> {
                    // Ép xem HUD (Turn-by-turn)
                    val targetHud = if (hud != null && hud.active) hud else {
                        NavigationRepository.HudData(
                            active = true,
                            isNavigation = true,
                            distance = "350m",
                            directions = "Rẽ phải vào Nguyễn Trãi",
                            iconIndex = 1,
                            eta = "18:45"
                        )
                    }
                    drawOledHudBitmap(targetHud)
                }
                2 -> {
                    // Ép xem Bản đồ 128x64 theo kiểu đang chọn (M1 Thuần Map, Pure Map 100%, M2 Chia đôi)
                    val source = (if (validLastImg != null && validLastImg.width == 128 && validLastImg.height == 64) validLastImg else null)
                        ?: validOledBase
                        ?: validPreviewCrop
                    when (oledMapSubStyle) {
                        0 -> drawOledMockupMapM1Bitmap()
                        1 -> drawOledMockupMapPureBitmap()
                        else -> if (source != null && !source.isRecycled) createOled1BitPreview(source) else drawOledMockupMapM2Bitmap()
                    }
                }
                else -> {
                    // Chế độ 0: Status Screen hoặc theo trạng thái tự động
                    if (isMapActive && (validLastImg != null || validOledBase != null || validPreviewCrop != null)) {
                        val source = (if (validLastImg != null && validLastImg.width == 128 && validLastImg.height == 64) validLastImg else null)
                            ?: validOledBase
                            ?: validPreviewCrop
                        if (source != null && !source.isRecycled) {
                            createOled1BitPreview(source)
                        } else {
                            when (oledMapSubStyle) {
                                0 -> drawOledMockupMapM1Bitmap()
                                1 -> drawOledMockupMapPureBitmap()
                                else -> drawOledMockupMapM2Bitmap()
                            }
                        }
                    } else if (hud != null && hud.active && hud.isNavigation) {
                        drawOledHudBitmap(hud)
                    } else {
                        drawOledStatusBitmap()
                    }
                }
            }

            binding.ivOledMainPreview.setImageBitmap(bmp)
            binding.tvOledWaitingPlaceholder.visibility = View.GONE

            // Cập nhật khung bản đồ gốc (Base Map) bên phải nếu có dữ liệu bản đồ
            val rightMapBmp = validOledBase ?: (if (validLastImg != null && validLastImg.width == 128 && validLastImg.height == 64) validLastImg else null) ?: validPreviewCrop
            if (rightMapBmp != null && !rightMapBmp.isRecycled) {
                binding.ivOledPreview.setImageBitmap(rightMapBmp)
            }

            val modeText = when (oledSimTabMode) {
                1 -> "DẪN ĐƯỜNG (HUD TURN)"
                2 -> when (oledMapSubStyle) {
                    0 -> "M1: THUẦN MAP TOÀN MÀN HÌNH"
                    1 -> "BẢN ĐỒ: CHỈ CÓ MAP (PURE 100%)"
                    else -> "M2: MAP CHIA ĐÔI KÈM HUD"
                }
                else -> {
                    if (isMapActive) "BẢN ĐỒ (128x64 MAP)"
                    else if (hud != null && hud.active && hud.isNavigation) "DẪN ĐƯỜNG (HUD TURN)"
                    else "ĐỒNG HỒ TỐC ĐỘ (STATUS)"
                }
            }
            val volt = NavigationRepository.deviceStatus.value["voltage"] ?: "12.6"
            val voltSuffix = if (volt.isNotBlank()) " • ${volt}V" else ""
            binding.tvOledStatusDetail.text = "Trạng thái: $modeText$voltSuffix"
        } catch (e: Exception) {
            android.util.Log.e("RenderFragment", "Error in refreshOledSimulator: ${e.message}", e)
        }
    }

    /**
     * MẪU 1: Thuần MAP Toàn Màn Hình 128x64 (M1)
     * Thiết kế chuẩn theo phác thảo:
     * - Khung bao toàn màn hình 128x64 viền 1px
     * - Góc trên trái: Text "MAP 128x64"
     * - Góc dưới trái: Thước đo cự ly "50m" và thanh gạch ngang
     * - Góc trên phải: Hộp chỉ dẫn Mini HUD chữ nhật bo góc với khoảng cách "150M" + icon rẽ "➔"
     * - Góc dưới phải: Tốc độ xe "42km/h"
     * - Giữa màn hình: Giao lộ ngã tư wireframe sắc nét, lộ trình rẽ phải 2.5px và mũi tên tâm xe
     */
    private fun drawOledMockupMapM1Bitmap(): Bitmap {
        val width = 128
        val height = 64
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF000000.toInt()) // OLED deep black

        val paint = Paint().apply { isAntiAlias = false }
        val white = 0xFFFFFFFF.toInt()
        paint.color = white

        // 1. Khung viền bao toàn màn hình 128x64
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRect(0f, 0f, 127f, 63f, paint)

        // 2. Góc trên bên trái: Nhãn "MAP 128x64"
        paint.style = Paint.Style.FILL
        paint.textSize = 7.5f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("MAP 128x64", 5f, 13f, paint)

        // 3. Góc dưới bên trái: Thước đo cự ly "50m"
        paint.textSize = 7f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        canvas.drawText("50m", 5f, 49f, paint)
        paint.strokeWidth = 1.5f
        canvas.drawLine(5f, 53f, 32f, 53f, paint)

        // 4. Lưới giao lộ ngã tư wireframe toàn màn hình
        paint.strokeWidth = 1f
        // Tuyến đường ngang qua toàn màn hình
        canvas.drawLine(0f, 36f, 127f, 36f, paint)
        // Tuyến đường dọc
        canvas.drawLine(64f, 0f, 64f, 63f, paint)

        // Lộ trình rẽ màu nét dày (Highlight Route: đi từ dưới lên ngã tư rồi rẽ phải)
        paint.strokeWidth = 2.5f
        canvas.drawLine(64f, 63f, 64f, 36f, paint)
        canvas.drawLine(64f, 36f, 127f, 36f, paint)

        // Mũi tên vị trí tâm xe (Car Position Arrow)
        paint.style = Paint.Style.FILL
        val carPath = Path().apply {
            moveTo(64f, 40f)
            lineTo(59f, 51f)
            lineTo(64f, 47f)
            lineTo(69f, 51f)
            close()
        }
        canvas.drawPath(carPath, paint)

        // 5. Góc trên bên phải: Hộp chỉ dẫn Mini HUD (150M ➔)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.2f
        canvas.drawRect(82f, 2f, 125f, 21f, paint)

        // Nội dung trong hộp Mini HUD
        paint.style = Paint.Style.FILL
        paint.textSize = 8.5f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val hud = NavigationRepository.hudPreviewData.value
        val distStr = if (hud != null && hud.active && hud.distance.isNotEmpty()) hud.distance.uppercase() else "150M"
        canvas.drawText(distStr, 85f, 15f, paint)

        // Mũi tên rẽ vector sắc nét trong Mini HUD
        val arrowPath = Path().apply {
            moveTo(114f, 10f)
            lineTo(121f, 14f)
            lineTo(114f, 18f)
            lineTo(116f, 14f)
            close()
        }
        canvas.drawPath(arrowPath, paint)

        // 6. Góc dưới bên phải: Tốc độ xe (42km/h)
        paint.textSize = 9.5f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val gpsSpeed = NavigationRepository.gpsLocation.value?.speed?.let { (it * 3.6f).toInt() } ?: 42
        canvas.drawText("${gpsSpeed}km/h", 78f, 52f, paint)

        return createOled1BitPreview(bmp)
    }

    /**
     * MẪU 2: Chỉ có MAP không (Pure Map 100%)
     * 100% không gian màn hình dành riêng cho bản đồ & lộ trình di chuyển:
     * - Tuyệt đối không hiển thị text, số, khung hay Mini HUD che khuất
     * - Lưới giao lộ đô thị đa trục (Urban Grid Wireframe)
     * - Lộ trình di chuyển nét đậm nổi bật
     * - Mũi tên định vị xe chính xác tại tâm
     */
    private fun drawOledMockupMapPureBitmap(): Bitmap {
        val width = 128
        val height = 64
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF000000.toInt()) // OLED deep black

        val paint = Paint().apply { isAntiAlias = false }
        val white = 0xFFFFFFFF.toInt()
        paint.color = white

        // 1. Viền màn hình 128x64
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRect(0f, 0f, 127f, 63f, paint)

        // 2. Các trục đường phố đô thị wireframe (tạo chiều sâu bản đồ đô thị)
        paint.strokeWidth = 1f
        // Các đường phố phụ song song
        canvas.drawLine(24f, 0f, 24f, 63f, paint)
        canvas.drawLine(104f, 0f, 104f, 63f, paint)
        canvas.drawLine(0f, 16f, 127f, 16f, paint)
        canvas.drawLine(0f, 52f, 127f, 52f, paint)

        // Trục giao lộ chính
        canvas.drawLine(0f, 34f, 127f, 34f, paint)
        canvas.drawLine(64f, 0f, 64f, 63f, paint)

        // 3. Lộ trình xe nét đậm 3px (Route Highlight)
        paint.strokeWidth = 3f
        canvas.drawLine(64f, 63f, 64f, 34f, paint)
        canvas.drawLine(64f, 34f, 127f, 34f, paint)

        // 4. Mũi tên vị trí xe
        paint.style = Paint.Style.FILL
        val carPath = Path().apply {
            moveTo(64f, 37f)
            lineTo(58f, 49f)
            lineTo(64f, 45f)
            lineTo(70f, 49f)
            close()
        }
        canvas.drawPath(carPath, paint)

        return createOled1BitPreview(bmp)
    }

    /**
     * MẪU 3: Map Chia Đôi Kèm HUD (M2 - Layout hiện tại)
     * Vách ngăn x=88: Bên trái 88x64 Map, Bên phải 40x64 Cụm HUD Turn + Tốc độ
     */
    private fun drawOledMockupMapM2Bitmap(): Bitmap {
        val width = 128
        val height = 64
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF000000.toInt()) // OLED deep black

        val paint = Paint().apply { isAntiAlias = false }
        val white = 0xFFFFFFFF.toInt()
        paint.color = white

        // 1. VÙNG BẢN ĐỒ WIREFRAME BÊN TRÁI (x = 0..87)
        // Khung viền bản đồ
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRect(0f, 0f, 87f, 63f, paint)

        // Lưới đường ngã tư wireframe
        canvas.drawLine(44f, 0f, 44f, 63f, paint)
        canvas.drawLine(0f, 32f, 87f, 32f, paint)

        // Lộ trình rẽ nét đậm 2px
        paint.strokeWidth = 2f
        canvas.drawLine(44f, 63f, 44f, 32f, paint)
        canvas.drawLine(44f, 32f, 87f, 32f, paint)

        // Vị trí tâm xe (Mũi tên wireframe)
        paint.style = Paint.Style.FILL
        val path = Path().apply {
            moveTo(44f, 25f)
            lineTo(39f, 36f)
            lineTo(44f, 33f)
            lineTo(49f, 36f)
            close()
        }
        canvas.drawPath(path, paint)

        // Nhãn tiêu đề Map bên trái
        paint.textSize = 8f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText("MAP", 4f, 10f, paint)

        // Thước đo góc dưới trái
        canvas.drawLine(4f, 58f, 24f, 58f, paint)
        paint.textSize = 6f
        canvas.drawText("50m", 6f, 55f, paint)

        // 2. VẠCH ĐỨNG PHÂN CÁCH CHUẨN CỦA FIRMWARE GUI.CPP TẠI x = 88
        paint.strokeWidth = 1f
        canvas.drawLine(88f, 0f, 88f, 63f, paint)

        // 3. VÙNG HUD CHIA ĐÔI BÊN PHẢI (x = 89..127)
        // Phần trên: Mini turn icon (x=100..116, y=4..20)
        val miniIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_nav_turn_right)
        miniIcon?.let {
            it.setBounds(100, 4, 116, 20)
            it.setTint(white)
            it.draw(canvas)
        }

        // Cự ly ngã rẽ
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        paint.textSize = 8.5f
        canvas.drawText("150M", 96f, 30f, paint)

        // Vạch gạch ngang phân cách giữa HUD trên và dưới tại y = 33
        canvas.drawLine(91f, 33f, 125f, 33f, paint)

        // Phần dưới: Tốc độ xe lớn + km/h
        paint.textSize = 13f
        canvas.drawText("42", 98f, 48f, paint)
        paint.textSize = 7f
        paint.typeface = Typeface.DEFAULT
        canvas.drawText("km/h", 97f, 58f, paint)

        return createOled1BitPreview(bmp)
    }

    private fun drawOledHudBitmap(hud: NavigationRepository.HudData): Bitmap {
        val width = 128
        val height = 64
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF000000.toInt()) // OLED deep black

        val paint = Paint().apply { isAntiAlias = false }
        val white = 0xFFFFFFFF.toInt()

        // 1. Khung & Icon rẽ bên trái (0..48, 0..64)
        val iconSize = 40
        val iconBmp: Bitmap? = if (hud.bitmapIcon != null) {
            hud.bitmapIcon
        } else if (hud.iconIndex >= 0) {
            val resId = maneuverIconRes(hud.iconIndex)
            val d = ContextCompat.getDrawable(requireContext(), resId)
            if (d != null) IconUtils.drawableToBitmap(d) else null
        } else null

        if (iconBmp != null) {
            val filterPaint = Paint().apply {
                isAntiAlias = false
                colorFilter = android.graphics.PorterDuffColorFilter(white, android.graphics.PorterDuff.Mode.SRC_IN)
            }
            val dstRect = Rect(4, (height - iconSize) / 2, 4 + iconSize, (height + iconSize) / 2)
            canvas.drawBitmap(iconBmp, null, dstRect, filterPaint)
        }

        // Vạch phân cách thẳng đứng ở x = 48
        paint.color = white
        paint.strokeWidth = 1f
        canvas.drawLine(48f, 2f, 48f, 62f, paint)

        // 2. Thông tin chỉ dẫn bên phải (49..127)
        // Dòng 1: Khoảng cách lớn
        paint.color = white
        paint.textSize = 14f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val distText = hud.distance.ifEmpty { "350M" }.uppercase()
        canvas.drawText(distText, 52f, 18f, paint)

        // Dòng 2: Tên đường / Chỉ dẫn tiếp theo
        paint.textSize = 9.5f
        paint.typeface = Typeface.DEFAULT
        val rawRoad = hud.directions.ifEmpty { hud.title }.ifEmpty { "Đi tiếp" }
        val roadText = if (rawRoad.length > 12) rawRoad.take(11) + "…" else rawRoad
        canvas.drawText(roadText, 52f, 33f, paint)

        // Dòng 3: Tốc độ hiện tại & ETA
        paint.textSize = 8.5f
        val gpsSpeed = NavigationRepository.gpsLocation.value?.speed?.let { (it * 3.6f).toInt() } ?: 42
        val etaText = hud.eta.ifEmpty { "18:30" }
        canvas.drawText("${gpsSpeed}km/h • $etaText", 52f, 47f, paint)

        // Dòng 4: Thanh tiến trình nhỏ ở đáy
        paint.style = Paint.Style.STROKE
        canvas.drawRect(52f, 54f, 124f, 60f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(52f, 54f, 88f, 60f, paint)

        return createOled1BitPreview(bmp)
    }

    private fun drawOledStatusBitmap(): Bitmap {
        val width = 128
        val height = 64
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF000000.toInt()) // OLED deep black

        val paint = Paint().apply { isAntiAlias = false }
        val white = 0xFFFFFFFF.toInt()
        paint.color = white

        // 1. Header (y: 2..13)
        val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        val timeStr = timeFormat.format(java.util.Date())
        paint.textSize = 8.5f
        paint.typeface = Typeface.MONOSPACE
        canvas.drawText(timeStr, 4f, 10f, paint)

        // Bluetooth status
        val bleConnected = NavigationRepository.bleConnectionState.value == NavigationRepository.BleConnectionState.Connected ||
                NavigationRepository.bleConnectionState.value == NavigationRepository.BleConnectionState.Ready
        paint.textSize = 8f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(if (bleConnected) "●BLE" else "○BLE", 48f, 10f, paint)

        // Điện áp bình ắc quy / pin
        val volt = NavigationRepository.deviceStatus.value["voltage"] ?: "12.6"
        val voltText = "${volt}V"
        paint.textSize = 8.5f
        paint.typeface = Typeface.MONOSPACE
        val vWidth = paint.measureText(voltText)
        canvas.drawText(voltText, 124f - vWidth, 10f, paint)

        // Vạch ngang ngăn cách header
        paint.strokeWidth = 1f
        canvas.drawLine(0f, 13f, 128f, 13f, paint)

        // 2. Đồng hồ tốc độ lớn ở trung tâm
        val realSpeed = NavigationRepository.gpsLocation.value?.speed?.let { (it * 3.6f).toInt() } ?: 0
        val gpsSpeed = if (realSpeed > 0) realSpeed else 42
        val speedStr = "$gpsSpeed"
        paint.textSize = 28f
        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        canvas.drawText(speedStr, 14f, 44f, paint)

        paint.textSize = 9f
        val sWidth = paint.measureText(speedStr)
        canvas.drawText("KM/H", 18f + sWidth, 34f, paint)

        // Nhãn thiết bị bên phải
        paint.textSize = 8f
        paint.typeface = Typeface.DEFAULT
        val display = NavigationRepository.deviceStatus.value["display"]?.takeIf { it.isNotBlank() } ?: "ESP32-C3 OLED"
        val dWidth = paint.measureText(display)
        canvas.drawText(display, 124f - dWidth, 42f, paint)

        // 3. Thanh đo tốc độ (Speed Gauge Bar) ở đáy (y: 52..60)
        paint.style = Paint.Style.STROKE
        canvas.drawRect(2f, 52f, 126f, 60f, paint)

        val barWidth = ((gpsSpeed.coerceIn(0, 120) * 122) / 120).toFloat()
        if (barWidth > 0) {
            paint.style = Paint.Style.FILL
            canvas.drawRect(3f, 53f, 3f + barWidth, 59f, paint)
        }

        return createOled1BitPreview(bmp)
    }

    private fun createOled1BitPreview(source: Bitmap): Bitmap {
        if (source.isRecycled) {
            return drawOledStatusBitmap()
        }
        return try {
            val scaled = Bitmap.createScaledBitmap(source, 128, 64, true)
            val preview = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
            val threshold = context?.let { PrefsHelper.getInt(it, "oled_threshold", 128) } ?: 128
            val invert = context?.let { PrefsHelper.getBoolean(it, "oled_invert", false) } ?: false

            for (y in 0 until 64) {
                for (x in 0 until 128) {
                    val pixel = scaled.getPixel(x, y)
                    val r = (pixel shr 16) and 0xFF
                    val g = (pixel shr 8) and 0xFF
                    val b = pixel and 0xFF
                    val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    val isWhite = if (invert) lum < threshold else lum >= threshold
                    // Màu Cyan Neon phát sáng đặc trưng trên màn hình OLED
                    val outColor = if (isWhite) 0xFF00E5FF.toInt() else 0xFF000000.toInt()
                    preview.setPixel(x, y, outColor)
                }
            }
            if (scaled != source && !scaled.isRecycled) scaled.recycle()
            preview
        } catch (e: Exception) {
            android.util.Log.e("RenderFragment", "Error creating OLED 1-bit preview: ${e.message}")
            drawOledStatusBitmap()
        }
    }

    private fun setupLogRecyclerView() {
        logAdapter = LogAdapter(logList)
        binding.rvRenderLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRenderLogs.adapter = logAdapter
    }

    private fun sendCurrentMapImage() {
        val service = NavigationService.activeInstance
        if (service != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val quality = PrefsHelper.getFloat(requireContext(), "jpeg_quality", 70f).toInt()
                val jpeg = service.renderOsmMap(quality)
                withContext(Dispatchers.Main) {
                    if (jpeg != null) {
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        if (bmp != null) {
                            NavigationRepository.updateOledBaseMap(bmp)
                            val oledBmp = createOled1BitPreview(bmp)
                            binding.ivOledMainPreview.setImageBitmap(oledBmp)
                            binding.tvOledWaitingPlaceholder.visibility = View.GONE
                        }
                        val bleManager = NavigationService.bleManager
                        if (bleManager != null && bleManager.isConnected) {
                            service.sendImageToDevice(jpeg)
                            Toast.makeText(requireContext(), "Đã cập nhật giả lập & gửi tới ESP32", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), "Đã cập nhật màn hình giả lập", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(requireContext(), "Không thể chụp bản đồ lúc này", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            val context = requireContext()
            try {
                val intent = android.content.Intent(context, NavigationService::class.java)
                context.startForegroundService(intent)
                Toast.makeText(context, "Đang khởi động dịch vụ bản đồ...", Toast.LENGTH_SHORT).show()
                binding.root.postDelayed({
                    sendCurrentMapImage()
                }, 800)
            } catch (e: Exception) {
                Toast.makeText(context, "Lỗi khởi động service: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sendDemoNavigationData() {
        val bleManager = NavigationService.bleManager
        if (bleManager != null && bleManager.isConnected) {
            val demoData = "active=1\nnav=1\ndist=350m\ntitle=Rẽ trái vào Nguyễn Huệ\nroad=Nguyễn Huệ\ndir=5\neta=18:30\nete=5 min"
            bleManager.writeNavigationData(demoData)
        }

        // Cập nhật trực tiếp lên màn hình giả lập để kiểm tra giao diện
        val mockHud = NavigationRepository.HudData(
            active = true,
            isNavigation = true,
            distance = "350m",
            duration = "5 min",
            eta = "18:30",
            title = "Rẽ trái vào Nguyễn Huệ",
            directions = "Nguyễn Huệ",
            iconIndex = 5
        )
        NavigationRepository.updateHudPreview(mockHud)
        refreshOledSimulator()
        Toast.makeText(requireContext(), "Đã nạp gói chỉ đường mẫu lên giả lập", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Nested Recycler Log Adapter for debug logs
    private class LogAdapter(private val logs: List<String>) :
        RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

        class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvLog: TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            // Cấu hình hiển thị chữ nhỏ và màu xanh dương/xám trên nền tối
            val tv = view.findViewById<TextView>(android.R.id.text1)
            tv.setTextColor(0xFF94A3B8.toInt())
            tv.typeface = android.graphics.Typeface.MONOSPACE
            tv.textSize = 11.5f
            tv.setPadding(6, 4, 6, 4)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            holder.tvLog.text = logs[position]
        }

        override fun getItemCount(): Int = logs.size
    }
}
