package com.example.tymap.ui

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.tymap.R
import com.example.tymap.databinding.FragmentSettingsBinding
import com.example.tymap.service.NavigationService
import com.example.tymap.utils.PrefsHelper
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (!allGranted) {
            Toast.makeText(requireContext(), "Cần tất cả quyền để app hoạt động", Toast.LENGTH_SHORT).show()
        }
        checkAllPermissionsStatus()
    }

    private val notificationAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkAllPermissionsStatus()
    }

    private val usageAccessLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkAllPermissionsStatus()
    }

    private val overlayLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkAllPermissionsStatus()
    }

    private val screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val ctx = context ?: return@registerForActivityResult
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(ctx, NavigationService::class.java).apply {
                action = "ACTION_START_CAPTURE"
                putExtra("PROJECTION_INTENT", result.data)
            }
            ctx.startForegroundService(serviceIntent)
            if (_binding != null) {
                val selectedMode = binding.spinnerMapCaptureMode.selectedItemPosition
                PrefsHelper.putInt(ctx, "map_capture_mode", selectedMode)
            }
        } else {
            if (_binding != null) {
                showPermissionDeniedDialog(ctx)
            }
        }
    }

    private fun showPermissionDeniedDialog(ctx: Context) {
        if (_binding == null) return
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("Quyền Chụp Màn Hình Bị Từ Chối")
            .setMessage("Để truyền hình ảnh Google Maps sang thiết bị, ứng dụng cần quyền ghi màn hình.\n\n" +
                    "⚠️ Hướng dẫn khắc phục:\n" +
                    "1. Hãy TẮT toàn bộ các ứng dụng vẽ đè (Bong bóng chat Messenger, Zalo...) nếu có trước khi thử lại.\n" +
                    "2. Đảm bảo bấm \"Bắt đầu ngay\" khi hộp thoại xác nhận hiện ra.")
            .setPositiveButton("Thử lại") { _, _ ->
                try {
                    ctx.stopService(Intent(ctx, com.example.tymap.service.CropOverlayService::class.java))
                } catch (e: Exception) {}
                
                // Start service first to ensure it is in foreground with mediaProjection type
                val startIntent = Intent(ctx, NavigationService::class.java)
                ctx.startForegroundService(startIntent)
                
                val mpm = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
            }
            .setNegativeButton("Hủy (Dùng OSM)") { _, _ ->
                if (_binding != null) {
                    binding.spinnerMapCaptureMode.setSelection(0)
                    PrefsHelper.putInt(ctx, "map_capture_mode", 0)
                    updateCropVisibility(0)
                }
            }
            .setCancelable(false)
            .show()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        setupFilterChips()
        observeDeviceType()
        observeServiceStatus()
    }

    private fun observeDeviceType() {
        viewLifecycleOwner.lifecycleScope.launch {
            com.example.tymap.repository.NavigationRepository.deviceStatus.collect { status ->
                val display = status["display"] ?: ""
                val isOled = display.contains("OLED") || display.contains("SSD1306")
                binding.layoutJpegOptions.visibility = if (isOled) View.GONE else View.VISIBLE
                
                if (isOled) {
                    val currentMode = PrefsHelper.getInt(requireContext(), "map_capture_mode", 0)
                    if (currentMode == 3) {
                        binding.spinnerMapCaptureMode.setSelection(0)
                        PrefsHelper.putInt(requireContext(), "map_capture_mode", 0)
                        PrefsHelper.putBoolean(requireContext(), "tile_streaming", false)
                        updateCropVisibility(0)
                        Toast.makeText(requireContext(), "Thiết bị OLED không hỗ trợ Tile Streaming. Chuyển về bản đồ tĩnh.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun observeServiceStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            com.example.tymap.repository.NavigationRepository.isServiceRunning.collect { running ->
                binding.switchServiceStatus.isChecked = running
            }
        }
    }

    private fun setupUI() {
        val context = requireContext()

        // 0. THEME
        val themes = arrayOf("Hệ thống", "Sáng", "Tối")
        val currentTheme = PrefsHelper.getInt(context, "app_theme", 0)
        setupSpinner(binding.spinnerTheme, themes, currentTheme) {
            PrefsHelper.putInt(context, "app_theme", it)
            applyTheme(it)
        }

        // 1. DISPLAY
        val timeFormats = arrayOf("12h", "24h")
        setupSpinner(binding.spinnerTimeFormat, timeFormats, PrefsHelper.getInt(context, "time_format", 1)) {
            PrefsHelper.putInt(context, "time_format", it)
        }

        val units = arrayOf("Hệ mét (km/m)", "Hệ Anh (mi/ft)")
        setupSpinner(binding.spinnerUnits, units, PrefsHelper.getInt(context, "units", 0)) {
            PrefsHelper.putInt(context, "units", it)
        }

        // 2. MAP
        val mapSources = arrayOf("CartoDB Positron", "OSM Mapnik", "CartoDB Dark Matter", "CartoDB Voyager", "Vệ tinh", "Tùy chỉnh (MapCN)")
        setupSpinner(binding.spinnerTileSource, mapSources, PrefsHelper.getInt(context, "tile_source", 0)) {
            PrefsHelper.putInt(context, "tile_source", it)
            binding.tilCustomTileUrl.visibility = if (it == 5) View.VISIBLE else View.GONE
        }

        binding.etCustomTileUrl.setText(PrefsHelper.getString(context, "custom_tile_url", ""))
        binding.etCustomTileUrl.addTextChangedListener {
            PrefsHelper.putString(context, "custom_tile_url", it.toString())
        }

        val initialZoom = PrefsHelper.getFloat(context, "default_zoom", 15f)
        binding.sliderDefaultZoom.value = initialZoom
        binding.tvValueDefaultZoom.text = "${initialZoom.toInt()}x"
        binding.sliderDefaultZoom.addOnChangeListener { _, value, _ -> 
            PrefsHelper.putFloat(context, "default_zoom", value)
            binding.tvValueDefaultZoom.text = "${value.toInt()}x"
        }

        binding.switchAutoZoom.isChecked = PrefsHelper.getBoolean(context, "auto_zoom", true)
        binding.switchAutoZoom.setOnCheckedChangeListener { _, isChecked -> 
            PrefsHelper.putBoolean(context, "auto_zoom", isChecked)
        }

        val orientations = arrayOf("Hướng Bắc", "Hướng đi")
        setupSpinner(binding.spinnerMapOrientation, orientations, PrefsHelper.getInt(context, "map_orientation", 1)) {
            PrefsHelper.putInt(context, "map_orientation", it)
        }

        binding.switchOfflinePriority.isChecked = PrefsHelper.getBoolean(context, "offline_priority", true)
        binding.switchOfflinePriority.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "offline_priority", isChecked)
        }

        binding.btnManageOfflineMaps.setOnClickListener {
            val intent = Intent(requireContext(), OfflineMapActivity::class.java)
            startActivity(intent)
        }



        // 3. ROUTING
        val engines = arrayOf("OSRM Demo", "OpenRouteService", "GraphHopper", "Valhalla", "Mapbox")
        setupSpinner(binding.spinnerRoutingEngine, engines, PrefsHelper.getInt(context, "routing_engine", 0)) {
            PrefsHelper.putInt(context, "routing_engine", it)
        }

        val vehicles = arrayOf("Ô tô", "Xe máy")
        setupSpinner(binding.spinnerVehicleType, vehicles, PrefsHelper.getInt(context, "vehicle_type", 0)) {
            PrefsHelper.putInt(context, "vehicle_type", it)
        }

        updateRoutingPriorityText()
        binding.btnConfigurePriority.setOnClickListener {
            showPriorityDialog()
        }

        val initialOffRoute = PrefsHelper.getFloat(context, "off_route_dist", 20f)
        binding.sliderOffRouteDist.value = initialOffRoute
        binding.tvValueOffRouteDist.text = "${initialOffRoute.toInt()} m"
        binding.sliderOffRouteDist.addOnChangeListener { _, value, _ -> 
            PrefsHelper.putFloat(context, "off_route_dist", value)
            binding.tvValueOffRouteDist.text = "${value.toInt()} m"
        }

        binding.switchSpeedWarning.isChecked = PrefsHelper.getBoolean(context, "speed_warning", false)
        binding.tilSpeedThreshold.visibility = if (binding.switchSpeedWarning.isChecked) View.VISIBLE else View.GONE
        binding.switchSpeedWarning.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "speed_warning", isChecked)
            binding.tilSpeedThreshold.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.etSpeedThreshold.setText(PrefsHelper.getInt(context, "speed_threshold", 60).toString())
        binding.etSpeedThreshold.addTextChangedListener {
            val v = it.toString().toIntOrNull() ?: 60
            PrefsHelper.putInt(context, "speed_threshold", v)
        }

        // 4. VOICE
        binding.switchVoiceGuidance.isChecked = PrefsHelper.getBoolean(context, "voice_guidance", true)
        binding.switchVoiceGuidance.setOnCheckedChangeListener { _, isChecked -> 
            PrefsHelper.putBoolean(context, "voice_guidance", isChecked)
        }

        val initialVolume = PrefsHelper.getFloat(context, "voice_volume", 80f)
        binding.sliderVoiceVolume.value = initialVolume
        binding.tvValueVoiceVolume.text = "${initialVolume.toInt()}%"
        binding.sliderVoiceVolume.addOnChangeListener { _, value, _ -> 
            PrefsHelper.putFloat(context, "voice_volume", value)
            binding.tvValueVoiceVolume.text = "${value.toInt()}%"
        }

        val voiceStyles = arrayOf("Đầy đủ", "Ngắn gọn")
        setupSpinner(binding.spinnerVoiceStyle, voiceStyles, PrefsHelper.getInt(context, "voice_style", 0)) {
            PrefsHelper.putInt(context, "voice_style", it)
        }

        binding.switchOffRouteAlert.isChecked = PrefsHelper.getBoolean(context, "voice_off_route", true)
        binding.switchOffRouteAlert.setOnCheckedChangeListener { _, isChecked -> 
            PrefsHelper.putBoolean(context, "voice_off_route", isChecked)
        }

        binding.switchSpeedWarningVoice.isChecked = PrefsHelper.getBoolean(context, "voice_speed_warning", true)
        binding.switchSpeedWarningVoice.setOnCheckedChangeListener { _, isChecked -> 
            PrefsHelper.putBoolean(context, "voice_speed_warning", isChecked)
        }

        val languages = arrayOf("Tiếng Việt", "English")
        setupSpinner(binding.spinnerLanguage, languages, PrefsHelper.getInt(context, "voice_language", 0)) {
            PrefsHelper.putInt(context, "voice_language", it)
        }

        // 5. DATA SENDING
        val captureModes = arrayOf(
            "Bản đồ OSM tĩnh (Continuous)",
            "Chụp Google Maps (Liên tục)",
            "Chụp Google Maps (Theo ngã rẽ/Popup)",
            "Bản đồ OSM cuốn chiếu (Tile Streaming)"
        )
        var initialMode = PrefsHelper.getInt(context, "map_capture_mode", 0)
        val isTileStreamingOld = PrefsHelper.getBoolean(context, "tile_streaming", false)
        if (isTileStreamingOld && initialMode == 0) {
            initialMode = 3
            PrefsHelper.putInt(context, "map_capture_mode", 3)
        }
        
        updateCropVisibility(initialMode)
        updateCropSummaries()

        var isFirstSelectionMapCapture = true
        setupSpinner(binding.spinnerMapCaptureMode, captureModes, initialMode) { mode ->
            PrefsHelper.putInt(context, "map_capture_mode", mode)
            if (mode == 3) {
                PrefsHelper.putBoolean(context, "tile_streaming", true)
            } else {
                PrefsHelper.putBoolean(context, "tile_streaming", false)
            }
            updateCropVisibility(mode)
            
            if (isFirstSelectionMapCapture) {
                isFirstSelectionMapCapture = false
            } else {
                if (mode == 1 || mode == 2) {
                    try {
                        requireContext().stopService(Intent(requireContext(), com.example.tymap.service.CropOverlayService::class.java))
                    } catch (e: Exception) {}
                    
                    // Start service first so it is running and foregrounded with mediaProjection type before permission request
                    val startIntent = Intent(requireContext(), NavigationService::class.java)
                    requireContext().startForegroundService(startIntent)
                    
                    val mpm = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
                }
            }
        }

        binding.btnConfigCropGmaps.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) {
                val serviceIntent = Intent(requireContext(), com.example.tymap.service.CropOverlayService::class.java).apply {
                    putExtra("CROP_TYPE", "gmaps")
                }
                requireContext().startService(serviceIntent)
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:${requireContext().packageName}")
                startActivity(intent)
                Toast.makeText(requireContext(), "Vui lòng cấp quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnConfigCropMapTab.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) {
                // Tự động chuyển sang tab Map (index 1) trước khi hiển thị khung cắt
                (activity as? com.example.tymap.MainActivity)?.selectTab(1)
                
                val serviceIntent = Intent(requireContext(), com.example.tymap.service.CropOverlayService::class.java).apply {
                    putExtra("CROP_TYPE", "map_tab")
                }
                requireContext().startService(serviceIntent)
            } else {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:${requireContext().packageName}")
                startActivity(intent)
                Toast.makeText(requireContext(), "Vui lòng cấp quyền hiển thị trên ứng dụng khác", Toast.LENGTH_LONG).show()
            }
        }

        val fpsList = arrayOf("1 FPS", "2 FPS", "3 FPS", "5 FPS", "7 FPS")
        setupSpinner(binding.spinnerMapFps, fpsList, PrefsHelper.getInt(context, "map_fps", 0)) {
            PrefsHelper.putInt(context, "map_fps", it)
        }

        val initialQuality = PrefsHelper.getFloat(context, "jpeg_quality", 40f)
        binding.sliderJpegQuality.value = initialQuality
        binding.tvValueJpegQuality.text = "${initialQuality.toInt()}%"
        binding.sliderJpegQuality.addOnChangeListener { _, value, _ -> 
            PrefsHelper.putFloat(context, "jpeg_quality", value)
            binding.tvValueJpegQuality.text = "${value.toInt()}%"
        }

        binding.switchFrameSkipping.isChecked = PrefsHelper.getBoolean(context, "frame_skipping", true)
        binding.switchFrameSkipping.setOnCheckedChangeListener { _, isChecked -> 
            PrefsHelper.putBoolean(context, "frame_skipping", isChecked)
        }

        // 5.1 POPUP SETTINGS
        binding.switchGmapsScreenshot.isChecked = PrefsHelper.getBoolean(context, "gmaps_screenshot_enabled", true)
        binding.switchGmapsScreenshot.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "gmaps_screenshot_enabled", isChecked)
        }

        val initialTrigger1 = PrefsHelper.getInt(context, "popup_trigger_1", 500)
        binding.sliderPopupTrigger1.value = initialTrigger1.toFloat()
        binding.tvValuePopupTrigger1.text = "${initialTrigger1} m"
        binding.sliderPopupTrigger1.addOnChangeListener { _, value, _ ->
            PrefsHelper.putInt(context, "popup_trigger_1", value.toInt())
            binding.tvValuePopupTrigger1.text = "${value.toInt()} m"
        }

        val initialTrigger2 = PrefsHelper.getInt(context, "popup_trigger_2", 200)
        binding.sliderPopupTrigger2.value = initialTrigger2.toFloat()
        binding.tvValuePopupTrigger2.text = "${initialTrigger2} m"
        binding.sliderPopupTrigger2.addOnChangeListener { _, value, _ ->
            PrefsHelper.putInt(context, "popup_trigger_2", value.toInt())
            binding.tvValuePopupTrigger2.text = "${value.toInt()} m"
        }

        val initialPopupDur = PrefsHelper.getInt(context, "popup_duration", 5)
        binding.sliderPopupDuration.value = initialPopupDur.toFloat()
        binding.tvValuePopupDuration.text = "${initialPopupDur} giây"
        binding.sliderPopupDuration.addOnChangeListener { _, value, _ ->
            PrefsHelper.putInt(context, "popup_duration", value.toInt())
            binding.tvValuePopupDuration.text = "${value.toInt()} giây"
            NavigationService.bleManager?.writeSettings("popupDuration=${value.toInt()}")
        }

        val initialHudTimeout = PrefsHelper.getInt(context, "hud_timeout", 3)
        binding.sliderHudTimeout.value = initialHudTimeout.toFloat()
        binding.tvValueHudTimeout.text = "${initialHudTimeout} giây"
        binding.sliderHudTimeout.addOnChangeListener { _, value, _ ->
            PrefsHelper.putInt(context, "hud_timeout", value.toInt())
            binding.tvValueHudTimeout.text = "${value.toInt()} giây"
            NavigationService.bleManager?.writeSettings("hudTimeout=${value.toInt()}")
        }

        // 5.5. OLED OPTIONS
        val initialOledThresh = PrefsHelper.getInt(context, "oled_threshold", 128)
        binding.sliderOledThreshold.value = initialOledThresh.toFloat()
        binding.tvValueOledThreshold.text = "${initialOledThresh}"
        binding.sliderOledThreshold.addOnChangeListener { _, value, _ ->
            PrefsHelper.putInt(context, "oled_threshold", value.toInt())
            binding.tvValueOledThreshold.text = "${value.toInt()}"
        }

        binding.switchOledDithering.isChecked = PrefsHelper.getBoolean(context, "oled_dithering", true)
        binding.switchOledDithering.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "oled_dithering", isChecked)
        }

        binding.switchOledInvert.isChecked = PrefsHelper.getBoolean(context, "oled_invert", false)
        binding.switchOledInvert.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "oled_invert", isChecked)
        }

        val oledFilterEnabled = PrefsHelper.getBoolean(context, "oled_color_filter", false)
        binding.switchOledColorFilter.isChecked = oledFilterEnabled
        binding.layoutOledColorFilterOptions.visibility = if (oledFilterEnabled) View.VISIBLE else View.GONE
        binding.switchOledColorFilter.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "oled_color_filter", isChecked)
            binding.layoutOledColorFilterOptions.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.etOledTargetColor.setText(PrefsHelper.getString(context, "oled_target_color", "#FFFFFF"))
        binding.etOledTargetColor.addTextChangedListener {
            val colorStr = it.toString().trim()
            PrefsHelper.putString(context, "oled_target_color", colorStr)
        }

        val initialOledTol = PrefsHelper.getInt(context, "oled_tolerance", 20)
        binding.sliderOledTolerance.value = initialOledTol.toFloat()
        binding.tvValueOledTolerance.text = "${initialOledTol}%"
        binding.sliderOledTolerance.addOnChangeListener { _, value, _ ->
            PrefsHelper.putInt(context, "oled_tolerance", value.toInt())
            binding.tvValueOledTolerance.text = "${value.toInt()}%"
        }

        binding.switchOledFilterInvert.isChecked = PrefsHelper.getBoolean(context, "oled_filter_invert", false)
        binding.switchOledFilterInvert.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "oled_filter_invert", isChecked)
        }

        binding.btnOledColorPicker.setOnClickListener {
            startActivity(Intent(requireContext(), OledColorFilterActivity::class.java))
        }

        // 6. SYSTEM
        binding.switchServiceStatus.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (areAllPermissionsGranted()) {
                    val serviceIntent = Intent(context, NavigationService::class.java)
                    context.startForegroundService(serviceIntent)
                } else {
                    requestAllMissingPermissions()
                    // Revert UI if permissions not ready (it will be updated by observer if granted)
                    binding.switchServiceStatus.isChecked = false
                }
            } else {
                val serviceIntent = Intent(context, NavigationService::class.java)
                context.stopService(serviceIntent)
            }
        }

        binding.btnOta.setOnClickListener {
            Toast.makeText(context, "Tính năng OTA đang phát triển", Toast.LENGTH_SHORT).show()
        }
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "1.0" }
        binding.tvVersion.text = "Phiên bản: $version"
    }

    private fun areAllPermissionsGranted(): Boolean {
        val context = requireContext()
        val runtimePermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runtimePermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            runtimePermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            runtimePermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtimePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runtimePermissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }

        val runtimeOk = runtimePermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        return runtimeOk && isNotificationAccessEnabled() && isUsageStatsEnabled() && Settings.canDrawOverlays(context)
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val pkgName = requireContext().packageName
        val flat = Settings.Secure.getString(requireContext().contentResolver, "enabled_notification_listeners")
        return flat?.contains(pkgName) == true
    }

    private fun isUsageStatsEnabled(): Boolean {
        val appOps = requireContext().getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), requireContext().packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun checkAllPermissionsStatus() {
        if (_binding != null) {
            val granted = areAllPermissionsGranted()
            binding.switchServiceStatus.isChecked = granted
            
            if (granted) {
                // Tự động khởi chạy service khi đã có đủ quyền
                val context = requireContext()
                val serviceIntent = Intent(context, NavigationService::class.java)
                context.startForegroundService(serviceIntent)
            }
        }
    }

    private fun requestAllMissingPermissions() {
        val context = requireContext()
        val runtimePermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runtimePermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            runtimePermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            runtimePermissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtimePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingRuntime = runtimePermissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingRuntime.isNotEmpty()) {
            permissionLauncher.launch(missingRuntime.toTypedArray())
            return
        }

        // Quyền foreground đã được cấp đủ, kiểm tra quyền chạy nền (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasBackground = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!hasBackground) {
                AlertDialog.Builder(context)
                    .setTitle("Cần quyền vị trí chạy nền")
                    .setMessage("Hãy chọn 'Cho phép lúc nào cũng vậy' (Allow all the time) trong trang Cài đặt tiếp theo để đảm bảo GPS hoạt động chính xác ngay cả khi tắt màn hình.")
                    .setPositiveButton("Cài đặt") { _, _ ->
                        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
                return
            }
        }

        if (!isNotificationAccessEnabled()) {
            AlertDialog.Builder(context)
                .setTitle("Cần quyền truy cập thông báo")
                .setMessage("App cần quyền này để bắt được thông báo từ Google Maps và các app khác.")
                .setPositiveButton("Cài đặt") { _, _ ->
                    notificationAccessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .show()
            return
        }

        if (!isUsageStatsEnabled()) {
            AlertDialog.Builder(context)
                .setTitle("Cần quyền truy cập sử dụng")
                .setMessage("App cần quyền này để biết khi nào Google Maps đang mở.")
                .setPositiveButton("Cài đặt") { _, _ ->
                    usageAccessLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .show()
            return
        }

        if (!Settings.canDrawOverlays(context)) {
            AlertDialog.Builder(context)
                .setTitle("Cần quyền hiển thị trên cùng")
                .setMessage("App cần quyền này để hiển thị khung chọn vùng cắt trên Google Maps.")
                .setPositiveButton("Cài đặt") { _, _ ->
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    intent.data = Uri.parse("package:${context.packageName}")
                    overlayLauncher.launch(intent)
                }
                .show()
            return
        }
    }

    private fun setupSpinner(spinner: android.widget.Spinner, items: Array<String>, selection: Int, onSelected: (Int) -> Unit) {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, items)
        spinner.adapter = adapter
        spinner.setSelection(selection)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, position: Int, p3: Long) {
                onSelected(position)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
    }

    private fun updateRoutingPriorityText() {
        val priority = PrefsHelper.getString(requireContext(), "routing_priority", "Mapbox,GraphHopper,Valhalla,OSRM")
        binding.tvRoutingPriority.text = priority.replace(",", " > ")
    }

    private fun showPriorityDialog() {
        val allEngines = arrayOf("Mapbox", "GraphHopper", "Valhalla", "OSRM", "OpenRouteService")
        val currentPriority = PrefsHelper.getString(requireContext(), "routing_priority", "Mapbox,GraphHopper,Valhalla,OSRM").split(",").toMutableList()
        val checkedItems = BooleanArray(allEngines.size) { i -> currentPriority.contains(allEngines[i]) }
        
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.fallback_priority)
            .setMultiChoiceItems(allEngines, checkedItems) { _, index, isChecked ->
                val engine = allEngines[index]
                if (isChecked) {
                    if (!currentPriority.contains(engine)) currentPriority.add(engine)
                } else {
                    currentPriority.remove(engine)
                }
            }
            .setPositiveButton("OK") { _, _ ->
                PrefsHelper.putString(requireContext(), "routing_priority", currentPriority.joinToString(","))
                updateRoutingPriorityText()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun applyTheme(themeMode: Int) {
        val mode = when (themeMode) {
            1 -> AppCompatDelegate.MODE_NIGHT_NO
            2 -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }


    override fun onResume() {
        super.onResume()
        updateCropSummaries()
    }

    private fun updateCropVisibility(mode: Int) {
        when (mode) {
            1, 2 -> {
                binding.btnConfigCropGmaps.visibility = View.VISIBLE
                binding.tvCropSummaryGmaps.visibility = View.VISIBLE
                binding.btnConfigCropMapTab.visibility = View.GONE
                binding.tvCropSummaryMapTab.visibility = View.GONE
            }
            0 -> {
                binding.btnConfigCropGmaps.visibility = View.GONE
                binding.tvCropSummaryGmaps.visibility = View.GONE
                binding.btnConfigCropMapTab.visibility = View.VISIBLE
                binding.tvCropSummaryMapTab.visibility = View.VISIBLE
            }
            else -> { // mode == 3 (Tile Streaming)
                binding.btnConfigCropGmaps.visibility = View.GONE
                binding.tvCropSummaryGmaps.visibility = View.GONE
                binding.btnConfigCropMapTab.visibility = View.GONE
                binding.tvCropSummaryMapTab.visibility = View.GONE
            }
        }
    }

    private fun updateCropSummaries() {
        val context = requireContext()
        val locale = Locale.getDefault()
        
        val gX = PrefsHelper.getFloat(context, "gmaps_crop_x_norm", -1f)
        if (gX >= 0) {
            val gY = PrefsHelper.getFloat(context, "gmaps_crop_y_norm", 0f)
            val gS = PrefsHelper.getFloat(context, "gmaps_crop_size_norm", 0f)
            binding.tvCropSummaryGmaps.text = String.format(locale, "Vị trí đã lưu: X:%.2f, Y:%.2f, Size:%.2f", gX, gY, gS)
        } else {
            binding.tvCropSummaryGmaps.text = "Vị trí đã lưu: Chưa cài đặt"
        }

        val mX = PrefsHelper.getFloat(context, "map_tab_crop_x_norm", -1f)
        if (mX >= 0) {
            val mY = PrefsHelper.getFloat(context, "map_tab_crop_y_norm", 0f)
            val mS = PrefsHelper.getFloat(context, "map_tab_crop_size_norm", 0f)
            binding.tvCropSummaryMapTab.text = String.format(locale, "Vị trí đã lưu: X:%.2f, Y:%.2f, Size:%.2f", mX, mY, mS)
        } else {
            binding.tvCropSummaryMapTab.text = "Vị trí đã lưu: Chưa cài đặt"
        }
    }

    private fun setupFilterChips() {
        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipAll
            
            // Ẩn tất cả card trước
            binding.cardGeneral.visibility = View.GONE
            binding.cardMap.visibility = View.GONE
            binding.cardRouting.visibility = View.GONE
            binding.cardVoice.visibility = View.GONE
            binding.cardMapTransfer.visibility = View.GONE
            binding.cardPopup.visibility = View.GONE
            binding.cardOled.visibility = View.GONE
            
            when (checkedId) {
                R.id.chipGeneral -> {
                    binding.cardGeneral.visibility = View.VISIBLE
                }
                R.id.chipMap -> {
                    binding.cardMap.visibility = View.VISIBLE
                }
                R.id.chipRouting -> {
                    binding.cardRouting.visibility = View.VISIBLE
                }
                R.id.chipVoice -> {
                    binding.cardVoice.visibility = View.VISIBLE
                }
                R.id.chipData -> {
                    binding.cardMapTransfer.visibility = View.VISIBLE
                    binding.cardPopup.visibility = View.VISIBLE
                    binding.cardOled.visibility = View.VISIBLE
                }
                else -> { // chipAll / mặc định hiển thị tất cả
                    binding.cardGeneral.visibility = View.VISIBLE
                    binding.cardMap.visibility = View.VISIBLE
                    binding.cardRouting.visibility = View.VISIBLE
                    binding.cardVoice.visibility = View.VISIBLE
                    binding.cardMapTransfer.visibility = View.VISIBLE
                    binding.cardPopup.visibility = View.VISIBLE
                    binding.cardOled.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
