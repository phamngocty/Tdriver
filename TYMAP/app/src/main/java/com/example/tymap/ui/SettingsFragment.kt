package com.example.tymap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tymap.R
import com.example.tymap.MainActivity
import com.example.tymap.databinding.FragmentSettingsBinding
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.service.NavigationService
import com.example.tymap.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var deviceAdapter: BluetoothDeviceAdapter
    private lateinit var apiServiceAdapter: ApiServiceAdapter
    private val apiServicesList = mutableListOf<ApiService>()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }
    private var isScanning = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
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
        checkAllPermissionsStatus()
    }

    private fun showPermissionDeniedDialog(ctx: Context) {
        if (_binding == null) return
        AlertDialog.Builder(ctx)
            .setTitle("Quyền Chụp Màn Hình Bị Từ Chối")
            .setMessage("Để truyền hình ảnh Google Maps sang thiết bị, ứng dụng cần quyền ghi màn hình.\n\n" +
                    "⚠️ Hướng dẫn khắc phục:\n" +
                    "1. Hãy TẮT toàn bộ các ứng dụng vẽ đè (Bong bóng chat Messenger, Zalo...) nếu có trước khi thử lại.\n" +
                    "2. Đảm bảo bấm \"Bắt đầu ngay\" khi hộp thoại xác nhận hiện ra.")
            .setPositiveButton("Thử lại") { _, _ ->
                try {
                    ctx.stopService(Intent(ctx, com.example.tymap.service.CropOverlayService::class.java))
                } catch (e: Exception) {}
                
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
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, insets.top, v.paddingRight, v.paddingBottom)
            windowInsets
        }
        setupBleConnectionUI()


        setupPermissionsDashboard()
        setupApiHealthDashboard()
        setupUI()
        setupFilterChips()
        observeDeviceType()
        observeServiceStatus()
        observeBleState()
    }

    // ----------------------------------------------------
    // 1. BLE CONNECTION & CONTROL SECTION
    // ----------------------------------------------------
    @SuppressLint("MissingPermission")
    private fun setupBleConnectionUI() {
        deviceAdapter = BluetoothDeviceAdapter { device ->
            val name = try { device.name ?: "Thiết bị không tên" } catch (e: SecurityException) { "Thiết bị" }
            Toast.makeText(requireContext(), "Đang kết nối đến: $name", Toast.LENGTH_SHORT).show()
            connectToDevice(device)
        }
        binding.rvDevices.adapter = deviceAdapter
        binding.rvDevices.layoutManager = LinearLayoutManager(requireContext())

        setupHistorySpinner()

        binding.btnScan.setOnClickListener {
            checkPermissionsAndScan()
        }

        binding.btnDisconnect.setOnClickListener {
            PrefsHelper.putString(requireContext(), "last_device_mac", "")
            NavigationService.bleManager?.disconnect()?.enqueue()
        }

        binding.btnSyncTime.setOnClickListener {
            val now = java.text.SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(java.util.Date())
            NavigationService.bleManager?.writeSettings("time=$now")
            Toast.makeText(requireContext(), "Đã gửi thời gian tới HUD", Toast.LENGTH_SHORT).show()
        }

        binding.btnSyncWeather.setOnClickListener {
            NavigationService.bleManager?.sendRemoteCommand(0x21)
            Toast.makeText(requireContext(), "Yêu cầu cập nhật thời tiết", Toast.LENGTH_SHORT).show()
        }

        binding.btnReqStatus.setOnClickListener {
            NavigationService.bleManager?.sendRemoteCommand(0x20)
            Toast.makeText(requireContext(), "Yêu cầu kiểm tra trạng thái HUD", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupHistorySpinner() {
        val context = context ?: return
        val historySet = PrefsHelper.getPairedHistory(context)
        val historyList = historySet.toMutableList()
        if (historyList.isEmpty()) {
            historyList.add("Chưa có thiết bị nào")
        } else {
            historyList.add(0, "Chọn thiết bị đã lưu...")
        }
        
        val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, historyList)
        binding.spinnerHistory.adapter = adapter
        
        binding.spinnerHistory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    val entry = historyList[position]
                    val mac = entry.substringAfter("(").substringBefore(")")
                    if (mac.length == 17) {
                        NavigationRepository.addLog("Kết nối lại tới $mac...")
                        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                        val device = manager.adapter.getRemoteDevice(mac)
                        connectToDevice(device)
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions)
        startScanning()
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            Toast.makeText(requireContext(), "Bật Bluetooth trước khi quét", Toast.LENGTH_SHORT).show()
            return
        }
        binding.scanProgress.visibility = View.VISIBLE
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                activity?.runOnUiThread { result.device?.let { deviceAdapter.addDevice(it) } }
            }
        }
        try {
            scanner.startScan(scanCallback)
            isScanning = true
            Handler(Looper.getMainLooper()).postDelayed({
                try { scanner.stopScan(scanCallback) } catch (e: Exception) {}
                isScanning = false
                if (_binding != null) binding.scanProgress.visibility = View.GONE
            }, 10000)
        } catch (e: Exception) {
            isScanning = false
            binding.scanProgress.visibility = View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val context = requireContext()
        PrefsHelper.putString(context, "last_device_mac", device.address)
        PrefsHelper.addPairedDevice(context, "${device.name ?: "Thiết bị"} (${device.address})")
        setupHistorySpinner()
        context.startForegroundService(Intent(context, NavigationService::class.java).apply { putExtra("CONNECT_MAC", device.address) })
    }

    private fun observeBleState() {
        viewLifecycleOwner.lifecycleScope.launch {
            NavigationRepository.bleConnectionState.collectLatest { updateConnectionStatusUI(it) }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            NavigationRepository.deviceStatus.collectLatest { status ->
                if (_binding == null) return@collectLatest
                binding.layoutDeviceInfo.visibility = if (status.isNotEmpty()) View.VISIBLE else View.GONE
                binding.tvDeviceName.text = status["name"] ?: "HUD ESP32"
                binding.tvRssi.text = "RSSI: ${status["rssi"] ?: "--"} dBm"
                binding.tvVoltage.text = "Pin xe: ${status["voltage"] ?: "--"}V"
                binding.tvEspMode.text = "Mode: ${status["mode"] ?: "--"}"
            }
        }
    }

    private fun updateConnectionStatusUI(state: NavigationRepository.BleConnectionState) {
        if (_binding == null) return
        if (state == NavigationRepository.BleConnectionState.Disconnected) {
            updateOledSettingsVisibility(false)
        }
        when (state) {
            NavigationRepository.BleConnectionState.Disconnected -> {
                binding.statusText.text = "Đã ngắt kết nối BLE"
                binding.statusIndicator.setBackgroundColor(Color.RED)
                binding.btnDisconnect.visibility = View.GONE
            }
            NavigationRepository.BleConnectionState.Connecting -> {
                binding.statusText.text = "Đang kết nối BLE..."
                binding.statusIndicator.setBackgroundColor(Color.YELLOW)
                binding.btnDisconnect.visibility = View.VISIBLE
            }
            NavigationRepository.BleConnectionState.Connected -> {
                binding.statusText.text = "Đã kết nối BLE"
                binding.statusIndicator.setBackgroundColor(Color.CYAN)
                binding.btnDisconnect.visibility = View.VISIBLE
            }
            NavigationRepository.BleConnectionState.Ready -> {
                binding.statusText.text = "Sẵn sàng truyền dữ liệu"
                binding.statusIndicator.setBackgroundColor(Color.GREEN)
                binding.btnDisconnect.visibility = View.VISIBLE
            }
        }
    }

    // ----------------------------------------------------
    // 2. SYSTEM PERMISSIONS DASHBOARD (CHECKBOX UI)
    // ----------------------------------------------------
    private fun setupPermissionsDashboard() {
        checkAllPermissionsStatus()
    }

    private fun checkAllPermissionsStatus() {
        if (_binding == null) return
        val context = context ?: return

        // 1. Location Permission CheckBox
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasBg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true
        val locationGranted = hasFine && hasBg

        setPermissionCheckBox(binding.cbPermLocation, locationGranted) {
            val locationPerms = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                locationPerms.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
            permissionLauncher.launch(locationPerms.toTypedArray())
        }

        // 2. Bluetooth Permission CheckBox
        val btGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true

        setPermissionCheckBox(binding.cbPermBluetooth, btGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissionLauncher.launch(arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ))
            } else {
                Toast.makeText(context, "Phiên bản Android này đã được mặc định cấp quyền Bluetooth", Toast.LENGTH_SHORT).show()
            }
        }

        // 3. Notification Listener CheckBox
        val notifGranted = isNotificationAccessEnabled()
        setPermissionCheckBox(binding.cbPermNotification, notifGranted) {
            notificationAccessLauncher.launch(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // 4. Screen Capture CheckBox
        val isCaptureActive = PrefsHelper.getInt(context, "map_capture_mode", 0) != 0
        setPermissionCheckBox(binding.cbPermScreenCapture, false) {
            val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureLauncher.launch(mpm.createScreenCaptureIntent())
        }

        // 5. Battery Optimization Exemption CheckBox
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val batteryIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
        setPermissionCheckBox(binding.cbPermBatteryOpt, batteryIgnored) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            startActivity(intent)
        }

        // 6. Draw Overlay CheckBox
        val overlayGranted = Settings.canDrawOverlays(context)
        setPermissionCheckBox(binding.cbPermOverlay, overlayGranted) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            overlayLauncher.launch(intent)
        }
    }

    private fun setPermissionCheckBox(
        checkBox: com.google.android.material.checkbox.MaterialCheckBox,
        isGranted: Boolean,
        onRequestPermission: () -> Unit
    ) {
        if (isGranted) {
            checkBox.isChecked = true
            checkBox.isEnabled = false
            checkBox.alpha = 0.5f
            checkBox.setOnClickListener(null)
        } else {
            checkBox.isChecked = false
            checkBox.isEnabled = true
            checkBox.alpha = 1.0f
            checkBox.setOnClickListener {
                onRequestPermission()
            }
        }
    }

    // ----------------------------------------------------
    // 3. NETWORK APIS & SERVICES DASHBOARD (AUTO-SAVE)
    // ----------------------------------------------------
    private fun setupApiHealthDashboard() {
        val context = requireContext()
        val orsKey = PrefsHelper.getSecureString(context, "api_key_ors", "")
        val ghKey = PrefsHelper.getSecureString(context, "api_key_gh", "")
        val stadiaKey = PrefsHelper.getSecureString(context, "api_key_stadia", "")
        val owmKey = PrefsHelper.getSecureString(context, "api_key_owm", "")
        val goongKey = PrefsHelper.getSecureString(context, "api_key_goong", "")
        val hereKey = PrefsHelper.getSecureString(context, "api_key_here", "")
        val tomtomKey = PrefsHelper.getSecureString(context, "api_key_tomtom", "")

        apiServicesList.clear()
        apiServicesList.addAll(listOf(
            ApiService("goong", "Goong.io API (Việt Nam)", "Cảnh báo biển báo, camera phạt nguội & dẫn đường Việt Nam.", "https://account.goong.io/", true, apiKey = goongKey, status = if (goongKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED),
            ApiService("overpass", "Overpass API (OSM Traffic)", "Cảnh báo camera & tốc độ miễn phí từ OpenStreetMap.", "https://overpass-api.de/", false, status = ServiceStatus.FREE),
            ApiService("here", "HERE Location Services", "Cung cấp giới hạn tốc độ & bản đồ nâng cao từ HERE.", "https://developer.here.com/", true, apiKey = hereKey, status = if (hereKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED),
            ApiService("tomtom", "TomTom API Services", "Cung cấp tốc độ giới hạn & bản đồ TomTom chuyên sâu.", "https://developer.tomtom.com/", true, apiKey = tomtomKey, status = if (tomtomKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED),
            ApiService("osrm", "OSRM Backend Engine", "Dẫn đường cực nhanh (Tự host / Demo miễn phí).", "https://router.project-osrm.org/", false, status = ServiceStatus.FREE),
            ApiService("valhalla", "Valhalla Routing Engine", "Dẫn đường đa phương tiện / tránh đường cao tốc.", "https://valhalla.opentripplanner.org/", false, status = ServiceStatus.FREE),
            ApiService("ors", "OpenRouteService (ORS)", "Dẫn đường chuyên sâu, yêu cầu API key.", "https://openrouteservice.org/dev/#/signup", true, apiKey = orsKey, status = if (orsKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED),
            ApiService("gh", "GraphHopper Routing", "Dẫn đường tối ưu xe máy, yêu cầu API key.", "https://www.graphhopper.com/", true, apiKey = ghKey, status = if (ghKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED),
            ApiService("photon", "Photon Autocomplete (Komoot)", "Tìm kiếm địa chỉ nhanh của Komoot, miễn phí.", "https://photon.komoot.io/", false, status = ServiceStatus.FREE),
            ApiService("pelias", "Pelias Geocoder", "Tìm kiếm địa chỉ nguồn mở (Geocode Earth).", "https://pelias.io/", false, status = ServiceStatus.FREE),
            ApiService("nominatim", "Nominatim Geocoder (OSM)", "Tìm kiếm vị trí mặc định từ OpenStreetMap.", "https://nominatim.org/", false, status = ServiceStatus.FREE),
            ApiService("open_meteo", "Open-Meteo Weather", "Dự báo thời tiết 10.000 req/ngày miễn phí.", "https://open-meteo.com/", false, status = ServiceStatus.FREE),
            ApiService("openweathermap", "OpenWeatherMap API", "Cung cấp thời tiết chính xác, yêu cầu API key.", "https://home.openweathermap.org/users/sign_up", true, apiKey = owmKey, status = if (owmKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED),
            ApiService("esri", "Esri World Canvas", "Bản đồ nền mượt OLED, miễn phí vô hạn.", "https://www.esri.com/", false, status = ServiceStatus.FREE),
            ApiService("stadia", "Stadia Alidade Smooth Dark", "Bản đồ tối mượt Alidade Smooth Dark cho OLED.", "https://stadiamaps.com/", true, apiKey = stadiaKey, status = if (stadiaKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED),
            ApiService("carto", "CartoDB Basemap", "Cung cấp bản đồ nền MapCN (Positron / Dark Matter / Voyager).", "https://carto.com/signup/", false, status = ServiceStatus.FREE)
        ))

        apiServiceAdapter = ApiServiceAdapter(
            apiServicesList,
            onTestClick = { service -> testApiService(service) },
            onRegisterClick = { service -> 
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(service.registrationUrl))
                startActivity(intent)
            },
            onKeyChanged = { service, newKey ->
                service.apiKey = newKey
                service.status = if (newKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED
                saveApiKey(service.id, newKey)
            }
        )
        binding.rvApiServices.adapter = apiServiceAdapter
        binding.rvApiServices.layoutManager = LinearLayoutManager(context)

        binding.btnCheckApiHealth.setOnClickListener {
            checkAllApiServicesHealth()
        }
    }

    private fun saveApiKey(serviceId: String, apiKey: String) {
        val context = context ?: return
        val keyName = when(serviceId) {
            "goong" -> "api_key_goong"
            "ors" -> "api_key_ors"
            "gh" -> "api_key_gh"
            "stadia" -> "api_key_stadia"
            "openweathermap" -> "api_key_owm"
            "here" -> "api_key_here"
            "tomtom" -> "api_key_tomtom"
            else -> null
        }
        keyName?.let { 
            PrefsHelper.putSecureString(context, it, apiKey)
        }
    }

    private fun testApiService(service: ApiService) {
        service.status = ServiceStatus.TESTING
        apiServiceAdapter.notifyDataSetChanged()
        
        saveApiKey(service.id, service.apiKey)
        
        lifecycleScope.launch(Dispatchers.IO) {
            val success = when(service.id) {
                "goong" -> testGoongKey(service.apiKey)
                "overpass" -> testOverpass()
                "here" -> testHereKey(service.apiKey)
                "tomtom" -> testTomTomKey(service.apiKey)
                "osrm" -> testOsrm()
                "ors" -> testOrsKey(service.apiKey)
                "gh" -> testGhKey(service.apiKey)
                "stadia" -> testStadiaKey(service.apiKey)
                "openweathermap" -> testOwmKey(service.apiKey)
                "open_meteo" -> testOpenMeteo()
                "photon" -> testPhoton()
                "pelias" -> testPelias()
                "esri" -> testEsri()
                else -> true
            }
            
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                service.status = if (success) {
                    if (service.isKeyRequired) ServiceStatus.CONFIGURED else ServiceStatus.FREE
                } else ServiceStatus.ERROR
                apiServiceAdapter.notifyDataSetChanged()
                
                val msg = if (success) "Kiểm tra thành công! Đã kết nối & lưu API Key." else "Kiểm tra thất bại. Vui lòng kiểm tra lại API Key hoặc mạng."
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkAllApiServicesHealth() {
        Toast.makeText(requireContext(), "Đang kiểm tra kết nối tất cả API...", Toast.LENGTH_SHORT).show()
        apiServicesList.forEach { service ->
            testApiService(service)
        }
    }

    private fun testGoongKey(key: String): Boolean {
        if (key.isEmpty()) return false
        val trimmedKey = key.trim()
        val endpoints = listOf(
            "https://rsapi.goong.io/geocode?address=Hanoi&api_key=$trimmedKey",
            "https://rsapi.goong.io/Place/AutoComplete?input=Hanoi&api_key=$trimmedKey",
            "https://rsapi.goong.io/Direction?origin=21.0285,105.8542&destination=21.0385,105.8642&vehicle=car&api_key=$trimmedKey"
        )
        val client = OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).readTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
        for (url in endpoints) {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "TYMAP/1.0").build()
                val resp = client.newCall(request).execute()
                val success = resp.isSuccessful
                resp.close()
                if (success) return true
            } catch (e: Exception) {}
        }
        return false
    }

    private fun testHereKey(key: String): Boolean {
        if (key.isBlank()) return false
        val url = "https://revgeocode.search.hereapi.com/v1/revgeocode?at=21.0285,105.8542&apiKey=$key"
        return try {
            val client = OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            val request = Request.Builder().url(url).build()
            val resp = client.newCall(request).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun testTomTomKey(key: String): Boolean {
        if (key.isBlank()) return false
        val url = "https://api.tomtom.com/search/2/reverseGeocode/21.0285,105.8542.json?key=$key"
        return try {
            val client = OkHttpClient.Builder().connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS).build()
            val request = Request.Builder().url(url).build()
            val resp = client.newCall(request).execute()
            val ok = resp.isSuccessful
            resp.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun testOverpass(): Boolean {
        val body = okhttp3.FormBody.Builder()
            .add("data", "[out:json][timeout:15];node[\"highway\"=\"speed_camera\"](around:1000,10.762622,106.660172);out body;")
            .build()
        val endpoints = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
        )
        val client = OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .post(body)
                    .header("User-Agent", "TYMAP/1.0 (Android Motorcycle Navigation)")
                    .build()
                val resp = client.newCall(request).execute()
                val success = resp.isSuccessful
                resp.close()
                if (success) return true
            } catch (e: Exception) {}
        }
        return false
    }

    private fun testOsrm(): Boolean {
        val url = "https://router.project-osrm.org/nearest/v1/driving/106.660172,10.762622"
        return try { OkHttpClient().newCall(Request.Builder().url(url).header("User-Agent", "TYMAP/1.0").build()).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    private fun testOrsKey(key: String): Boolean {
        if (key.isEmpty()) return false
        val url = "https://api.openrouteservice.org/v2/directions/driving-car?api_key=$key&start=106.660172,10.762622&end=106.670172,10.772622"
        return try { OkHttpClient().newCall(Request.Builder().url(url).header("User-Agent", "TYMAP/1.0").build()).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    private fun testGhKey(key: String): Boolean {
        if (key.isEmpty()) return false
        val url = "https://graphhopper.com/api/1/route?point=10.762622,106.660172&point=10.772622,106.670172&profile=car&locale=vi&key=$key"
        val request = Request.Builder().url(url).header("User-Agent", "TYMAP/1.0").build()
        return try { OkHttpClient().newCall(request).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    private fun testStadiaKey(key: String): Boolean {
        val url = "https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/0/0/0.png" + if (key.isNotEmpty()) "?api_key=$key" else ""
        return try { OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    private fun testOwmKey(key: String): Boolean {
        if (key.isEmpty()) return false
        val url = "https://api.openweathermap.org/data/2.5/weather?lat=10.762622&lon=106.660172&appid=$key"
        return try { OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    private fun testOpenMeteo(): Boolean {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=10.762622&longitude=106.660172&current_weather=true"
        return try { OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    private fun testPhoton(): Boolean {
        val url = "https://photon.komoot.io/api/?q=Ho+Chi+Minh&limit=1"
        return try { OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    private fun testPelias(): Boolean {
        val url = "https://api.geocode.earth/v1/autocomplete?text=Ho+Chi+Minh&size=1"
        return try { OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful || it.code == 401 } } catch (e: Exception) { false }
    }

    private fun testEsri(): Boolean {
        val url = "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/0/0/0"
        return try { OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    // ----------------------------------------------------
    // 4. EXISTING SETTINGS SETUP
    // ----------------------------------------------------

    private fun updateOledSettingsVisibility(isOledConnected: Boolean = false) {
        if (_binding == null) return
        val manualShow = PrefsHelper.getBoolean(requireContext(), "manual_show_oled", false)
        val shouldShow = isOledConnected || manualShow
        binding.layoutOledSettings.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }

    private fun observeDeviceType() {
        viewLifecycleOwner.lifecycleScope.launch {
            NavigationRepository.deviceStatus.collect { status ->
                val display = status["display"] ?: ""
                val isOled = display.contains("OLED") || display.contains("SSD1306")
                
                updateOledSettingsVisibility(isOled)
                
                if (isOled && _binding != null) {
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
            NavigationRepository.isServiceRunning.collect { running ->
                if (_binding != null) {
                    binding.switchServiceStatus.isChecked = running
                }
            }
        }
    }

    private fun setupUI() {
        val context = requireContext()
        
        binding.switchManualShowOled.isChecked = PrefsHelper.getBoolean(context, "manual_show_oled", false)
        binding.switchManualShowOled.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "manual_show_oled", isChecked)
            updateOledSettingsVisibility()
        }
        updateOledSettingsVisibility()


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

        val statusStyles = arrayOf("Mẫu S4: Cyber Dual Gauges (Mặc định)", "Mẫu S5: Classic Analog Watch", "Mẫu S3: Dual Energy Pill", "Mẫu S6: Classic", "Mẫu S7: Sport")
        setupSpinner(binding.spinnerStatusStyle, statusStyles, PrefsHelper.getInt(context, "status_style", 0)) { styleIdx ->
            PrefsHelper.putInt(context, "status_style", styleIdx)
            NavigationService.bleManager?.writeSettings("statusStyle=$styleIdx")
            Toast.makeText(context, "Đã gửi cấu hình Mẫu STATUS!", Toast.LENGTH_SHORT).show()
        }

        val notifStyles = arrayOf("Mẫu N1: Floating Card 3D", "Mẫu N2: Fullscreen Focus (Mặc định)", "Mẫu N3: Mini Popup", "Mẫu N4: Thẻ cuộn")
        setupSpinner(binding.spinnerNotifStyle, notifStyles, PrefsHelper.getInt(context, "notif_style", 1)) { styleIdx ->
            PrefsHelper.putInt(context, "notif_style", styleIdx)
            NavigationService.bleManager?.writeSettings("notifStyle=$styleIdx")
            Toast.makeText(context, "Đã gửi cấu hình Mẫu NOTIF!", Toast.LENGTH_SHORT).show()
        }

        val hudTimeoutOptions = arrayOf("Vĩnh viễn (Không tự đóng)", "10 giây", "30 giây", "1 phút (60s)", "3 phút (180s)", "5 phút (300s)")
        val hudTimeoutValues = arrayOf(0, 10, 30, 60, 180, 300)
        val currentHudTimeout = PrefsHelper.getInt(context, "hud_timeout_val", 0)
        val hudIdx = hudTimeoutValues.indexOf(currentHudTimeout).let { if (it >= 0) it else 0 }
        setupSpinner(binding.
        spinnerHudTimeout, hudTimeoutOptions, hudIdx) { selectedIdx ->
            val secVal = hudTimeoutValues[selectedIdx]
            PrefsHelper.putInt(context, "hud_timeout_val", secVal)
            NavigationService.bleManager?.writeSettings("hudTimeout=$secVal")
            Toast.makeText(context, "Đã cài đặt Thời gian đóng HUD: ${hudTimeoutOptions[selectedIdx]}", Toast.LENGTH_SHORT).show()
        }

        val mapHudStyles = arrayOf("Mẫu MH1: Compact Floating Pill (Mặc định)", "Mẫu MH2: Thanh Dưới", "Mẫu MH3: Big Turn", "Mẫu MH4: Mini HUD", "Mẫu MH5: Bản đồ thuần")
        setupSpinner(binding.spinnerMapHudStyle, mapHudStyles, PrefsHelper.getInt(context, "map_hud_style", 0)) { styleIdx ->
            PrefsHelper.putInt(context, "map_hud_style", styleIdx)
            NavigationService.bleManager?.writeSettings("mapHudStyle=$styleIdx")
            Toast.makeText(context, "Đã gửi cấu hình Mẫu MAP HUD!", Toast.LENGTH_SHORT).show()
        }

        // 2. MAP
        val mapSources = arrayOf("CartoDB Positron", "CartoDB Dark Matter", "CartoDB Voyager", "Google Maps (MT)", "Google Maps Dark (MT)", "Google Maps Đảo Màu (MT Invert)", "Google Maps Satellite (MT)", "Google Maps Hybrid (MT)", "Tùy chỉnh (MapCN/Self-Hosted)")
        val rawTileSource = PrefsHelper.getInt(context, "tile_source", 0)
        val initialTileSource = if (rawTileSource >= mapSources.size) 0 else rawTileSource
        binding.tilCustomTileUrl.visibility = if (initialTileSource == 8) View.VISIBLE else View.GONE
        setupSpinner(binding.spinnerTileSource, mapSources, initialTileSource) {
            PrefsHelper.putInt(context, "tile_source", it)
            binding.tilCustomTileUrl.visibility = if (it == 8) View.VISIBLE else View.GONE
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

        binding.switchInvertHeading.isChecked = PrefsHelper.getBoolean(context, "invert_heading", false)
        binding.switchInvertHeading.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "invert_heading", isChecked)
            Toast.makeText(context, if (isChecked) "Đã bật đảo chiều la bàn khi đứng yên (180°)" else "Đã tắt đảo chiều la bàn khi đứng yên", Toast.LENGTH_SHORT).show()
        }

        val compassOffsets = arrayOf("0° (Mặc định)", "90° (Lệch phải)", "180° (Đảo ngược)", "270° / -90° (Lệch trái)")
        val currentOffsetIndex = when (PrefsHelper.getInt(context, "compass_offset_mode", 0)) {
            90 -> 1
            180 -> 2
            270 -> 3
            else -> 0
        }
        setupSpinner(binding.spinnerCompassOffset, compassOffsets, currentOffsetIndex) { index ->
            val angleMode = when (index) {
                1 -> 90
                2 -> 180
                3 -> 270
                else -> 0
            }
            PrefsHelper.putInt(context, "compass_offset_mode", angleMode)
        }

        binding.btnManageOfflineMaps.setOnClickListener {
            val intent = Intent(requireContext(), OfflineMapActivity::class.java)
            startActivity(intent)
        }

        // 3. ROUTING
        val engines = arrayOf("OSRM Demo", "OpenRouteService", "GraphHopper", "Valhalla", "Tùy chỉnh (Self-Hosted OSRM)")
        val initialEngine = PrefsHelper.getInt(context, "routing_engine", 0)
        binding.tilCustomRoutingUrl.visibility = if (initialEngine == 4) View.VISIBLE else View.GONE
        setupSpinner(binding.spinnerRoutingEngine, engines, initialEngine) {
            PrefsHelper.putInt(context, "routing_engine", it)
            binding.tilCustomRoutingUrl.visibility = if (it == 4) View.VISIBLE else View.GONE
        }

        binding.etCustomRoutingUrl.setText(PrefsHelper.getString(context, "custom_routing_url", ""))
        binding.etCustomRoutingUrl.addTextChangedListener {
            PrefsHelper.putString(context, "custom_routing_url", it.toString())
        }

        binding.etCustomSearchUrl.setText(PrefsHelper.getString(context, "custom_search_url", ""))
        binding.etCustomSearchUrl.addTextChangedListener {
            PrefsHelper.putString(context, "custom_search_url", it.toString())
        }

        val vehicles = arrayOf("Ô tô", "Xe máy")
        setupSpinner(binding.spinnerVehicleType, vehicles, PrefsHelper.getInt(context, "vehicle_type", 0)) {
            PrefsHelper.putInt(context, "vehicle_type", it)
        }

        val initialOffRoute = PrefsHelper.getFloat(context, "off_route_dist", 20f)
        binding.sliderOffRouteDist.value = initialOffRoute.coerceIn(10f, 50f)
        binding.tvValueOffRouteDist.text = "${initialOffRoute.toInt()} m"

        binding.sliderOffRouteDist.addOnChangeListener { _, value, _ -> 
            PrefsHelper.putFloat(context, "off_route_dist", value)
            binding.tvValueOffRouteDist.text = "${value.toInt()} m"
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

        binding.switchOffRouteAlert.isChecked = PrefsHelper.getBoolean(context, "voice_off_route", true)
        binding.switchOffRouteAlert.setOnCheckedChangeListener { _, isChecked -> 
            PrefsHelper.putBoolean(context, "voice_off_route", isChecked)
        }

        val languages = arrayOf("Tiếng Việt", "English")
        setupSpinner(binding.spinnerLanguage, languages, PrefsHelper.getInt(context, "voice_language", 0)) {
            PrefsHelper.putInt(context, "voice_language", it)
        }

        // 5. DATA SENDING
        val captureModes = arrayOf(
            "Bản đồ OSM tĩnh (Continuous)",
            "Google Maps Popup OSM (Popup ngã rẽ)"
        )
        val rawMode = PrefsHelper.getInt(context, "map_capture_mode", 5)
        val initialIndex = if (rawMode == 0) 0 else 1
        
        updateCropVisibility(rawMode)
        updateCropSummaries()

        setupSpinner(binding.spinnerMapCaptureMode, captureModes, initialIndex) { position ->
            val selectedMode = if (position == 0) 0 else 5
            PrefsHelper.putInt(context, "map_capture_mode", selectedMode)
            updateCropVisibility(selectedMode)
        }

        binding.btnConfigCropMapTab.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) {
                (activity as? MainActivity)?.selectTab(0)
                
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

        val fpsList = arrayOf("Tự động (Smart)", "Tối đa (Max)")
        val rawFps = PrefsHelper.getInt(context, "map_fps", 0)
        val initialFps = if (rawFps > 1) 0 else rawFps
        setupSpinner(binding.spinnerMapFps, fpsList, initialFps) { position ->
            PrefsHelper.putInt(context, "map_fps", position)
            binding.tvMapFpsDesc.text = if (position == 0) {
                "Khi dừng: 1 FPS để tiết kiệm pin. Khi đi: Tự động tăng lên 15-20 FPS mượt mà. Tự giảm FPS nếu BLE yếu để chống treo mạch."
            } else {
                "Luôn truyền ở tốc độ cao nhất (lên tới 20 FPS). Chỉ tự động giảm nếu BLE bị quá tải hoặc nhiễu để tránh treo mạch."
            }
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

        // 6. SYSTEM SERVICE SWITCH
        binding.switchServiceStatus.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val serviceIntent = Intent(context, NavigationService::class.java)
                context.startForegroundService(serviceIntent)
            } else {
                val serviceIntent = Intent(context, NavigationService::class.java)
                context.stopService(serviceIntent)
            }
        }

        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "1.0" }
        binding.tvVersion.text = "Phiên bản: $version"

        // NÂNG CAO: toggle mở rộng các tùy chọn kỹ thuật
        binding.toggleMapAdvanced.setOnClickListener {
            val show = binding.layoutMapAdvanced.visibility != View.VISIBLE
            binding.layoutMapAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            binding.toggleMapAdvanced.text = if (show) "Tùy chọn la bàn & khung hình  ▾" else "Tùy chọn la bàn & khung hình  ▸"
        }
        binding.toggleApiAdvanced.setOnClickListener {
            val show = binding.layoutApiAdvanced.visibility != View.VISIBLE
            binding.layoutApiAdvanced.visibility = if (show) View.VISIBLE else View.GONE
            binding.toggleApiAdvanced.text = if (show) "Quân lý dịch vụ mạng & API  ▲" else "Quân lý dịch vụ mạng & API  ▼"
        }

        binding.layoutFrameSkipping.visibility = View.GONE
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val pkgName = requireContext().packageName
        val flat = Settings.Secure.getString(requireContext().contentResolver, "enabled_notification_listeners")
        return flat?.contains(pkgName) == true
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
        checkAllPermissionsStatus()
    }

    private fun updateCropVisibility(mode: Int) {
        binding.btnConfigCropMapTab.visibility = View.VISIBLE
        binding.tvCropSummaryMapTab.visibility = View.VISIBLE
    }

    private fun updateCropSummaries() {
        val context = context ?: return
        val locale = Locale.getDefault()
        
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
        var versionTapCount = 0
        binding.tvVersion.setOnClickListener {
            versionTapCount++
            if (versionTapCount >= 5) {
                versionTapCount = 0
                val currentShow = PrefsHelper.getBoolean(requireContext(), "render_tab_unlocked", false)
                val newShow = !currentShow
                PrefsHelper.putBoolean(requireContext(), "render_tab_unlocked", newShow)
                (activity as? MainActivity)?.updateRenderTabVisibility()
                val msg = if (newShow) "Đã HIỆN Tab Render trên thanh điều hướng!" else "Đã ẨN Tab Render!"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.chipGroupFilter.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.firstOrNull() ?: R.id.chipAll
            
            binding.cardGeneral.visibility = View.GONE
            binding.cardMap.visibility = View.GONE
            binding.cardRouting.visibility = View.GONE
            binding.cardOled.visibility = View.GONE
            binding.cardSystem.visibility = View.GONE
            
            when (checkedId) {
                R.id.chipGeneral -> binding.cardGeneral.visibility = View.VISIBLE
                R.id.chipMap -> binding.cardMap.visibility = View.VISIBLE
                R.id.chipRouting -> binding.cardRouting.visibility = View.VISIBLE
                R.id.chipData -> binding.cardOled.visibility = View.VISIBLE
                R.id.chipSystem -> binding.cardSystem.visibility = View.VISIBLE
                else -> {
                    binding.cardGeneral.visibility = View.VISIBLE
                    binding.cardMap.visibility = View.VISIBLE
                    binding.cardRouting.visibility = View.VISIBLE
                    binding.cardOled.visibility = View.VISIBLE
                    binding.cardSystem.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
