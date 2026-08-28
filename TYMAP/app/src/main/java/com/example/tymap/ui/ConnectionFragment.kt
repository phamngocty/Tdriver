package com.example.tymap.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tymap.R
import com.example.tymap.databinding.FragmentConnectionBinding
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.service.NavigationService
import com.example.tymap.utils.PrefsHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

import android.graphics.Color
import androidx.core.content.ContextCompat
import com.example.tymap.ui.LogAdapter

class ConnectionFragment : Fragment() {
    private var _binding: FragmentConnectionBinding? = null
    private val binding get() = _binding!!
    private lateinit var deviceAdapter: BluetoothDeviceAdapter
    private lateinit var apiServiceAdapter: ApiServiceAdapter
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = requireContext().getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }
    private var isScanning = false
    private var isLogPaused = false
    private var isSyncingToggle = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startScanning()
            // Force service to restart GPS with new permissions
            val intent = Intent(requireContext(), NavigationService::class.java)
            requireContext().startForegroundService(intent)
        } else {
            Toast.makeText(requireContext(), "Cần cấp quyền để ứng dụng hoạt động chính xác", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConnectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerViews()
        setupHistory()
        setupListeners()
        observeNavigationData()
        checkNotificationPermission()
    }

    private fun checkNotificationPermission() {
        val enabled = android.provider.Settings.Secure.getString(
            requireContext().contentResolver,
            "enabled_notification_listeners"
        )?.contains(requireContext().packageName) == true
        
        if (!enabled) {
            binding.btnNotificationAccess?.text = "CHƯA CẤP QUYỀN THÔNG BÁO"
            binding.btnNotificationAccess?.setBackgroundColor(Color.RED)
        } else {
            binding.btnNotificationAccess?.text = "Đã cấp quyền thông báo"
            binding.btnNotificationAccess?.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.blue_primary))
        }
    }

    private fun setupHistory() {
        val context = requireContext()
        val historySet = PrefsHelper.getPairedHistory(context)
        val historyList = historySet.toMutableList()
        if (historyList.isEmpty()) {
            historyList.add("Chưa có thiết bị nào")
        } else {
            historyList.add(0, "Chọn thiết bị cũ...")
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

    @SuppressLint("MissingPermission")
    private fun setupRecyclerViews() {
        deviceAdapter = BluetoothDeviceAdapter { device ->
            val name = try { device.name ?: "Thiết bị không tên" } catch (e: SecurityException) { "Thiết bị" }
            Toast.makeText(requireContext(), "Chọn: $name", Toast.LENGTH_SHORT).show()
            connectToDevice(device)
        }
        binding.rvDevices.adapter = deviceAdapter
        binding.rvDevices.layoutManager = LinearLayoutManager(requireContext())

        val context = requireContext()
        val orsKey = PrefsHelper.getSecureString(context, "api_key_ors", "")
        val ghKey = PrefsHelper.getSecureString(context, "api_key_gh", "")
        val stadiaKey = PrefsHelper.getSecureString(context, "api_key_stadia", "")
        val cartoKey = PrefsHelper.getSecureString(context, "api_key_carto", "")

        val apiServices = listOf(
            ApiService("osrm", "OSRM Backend", "Dẫn đường cực nhanh (Tự host / Demo miễn phí).", "https://router.project-osrm.org/", false, status = ServiceStatus.FREE),
            ApiService("valhalla", "Valhalla Routing Engine", "Dẫn đường đa phương tiện / tránh đường cao tốc.", "https://valhalla.opentripplanner.org/", false, status = ServiceStatus.FREE),
            ApiService("ors", "OpenRouteService (ORS)", "Dẫn đường chuyên sâu, yêu cầu API key.", "https://openrouteservice.org/dev/#/signup", true, apiKey = orsKey, status = if (orsKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED),
            ApiService("gh", "GraphHopper Routing", "Dẫn đường tối ưu xe máy, yêu cầu API key.", "https://www.graphhopper.com/", true, apiKey = ghKey, status = if (ghKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED),
            ApiService("photon", "Photon Autocomplete (Komoot)", "Tìm kiếm địa chỉ nhanh của Komoot, miễn phí.", "https://photon.komoot.io/", false, status = ServiceStatus.FREE),
            ApiService("nominatim", "Nominatim Geocoder (OSM)", "Tìm kiếm vị trí mặc định từ OpenStreetMap.", "https://nominatim.org/", false, status = ServiceStatus.FREE),
            ApiService("open_meteo", "Open-Meteo Weather", "Dự báo thời tiết 10.000 req/ngày miễn phí.", "https://open-meteo.com/", false, status = ServiceStatus.FREE),
            ApiService("esri", "Esri World Canvas", "Bản đồ nền mượt OLED, miễn phí vô hạn.", "https://www.esri.com/", false, status = ServiceStatus.FREE),
            ApiService("stadia", "Stadia Alidade Smooth Dark", "Bản đồ tối mượt Alidade Smooth Dark cho OLED.", "https://stadiamaps.com/", true, apiKey = stadiaKey, status = if (stadiaKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED),
            ApiService("carto", "CartoDB Basemap (MapCN)", "Cung cấp bản đồ Positron / Dark Matter / Voyager. Nhập API Key miễn phí để tắt chữ mờ watermark.", "https://carto.com/signup/", true, apiKey = cartoKey, status = if (cartoKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED)
        )
        
        apiServiceAdapter = ApiServiceAdapter(
            apiServices,
            onTestClick = { service -> testApiService(service) },
            onRegisterClick = { service -> 
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(service.registrationUrl))
                startActivity(intent)
            },
            onKeyChanged = { service, newKey ->
                service.apiKey = newKey
                service.status = if (newKey.isNotEmpty()) ServiceStatus.CONFIGURED else ServiceStatus.NOT_CONFIGURED
                val keyName = when(service.id) {
                    "ors" -> "api_key_ors"
                    "gh" -> "api_key_gh"
                    "stadia" -> "api_key_stadia"
                    "carto" -> "api_key_carto"
                    else -> null
                }
                keyName?.let { 
                    PrefsHelper.putSecureString(context, it, newKey)
                    android.util.Log.d("ConnectionFragment", "Saved API Key for $it: ${newKey.take(5)}...")
                }
            }
        )
        binding.rvApiServices.adapter = apiServiceAdapter
        binding.rvApiServices.layoutManager = LinearLayoutManager(context)

        binding.rvLogs.layoutManager = LinearLayoutManager(context)
    }

    private fun testApiService(service: ApiService) {
        service.status = ServiceStatus.TESTING
        apiServiceAdapter.notifyDataSetChanged()
        
        val context = requireContext()
        val keyName = when(service.id) {
            "ors" -> "api_key_ors"
            "gh" -> "api_key_gh"
            "stadia" -> "api_key_stadia"
            "carto" -> "api_key_carto"
            else -> null
        }
        keyName?.let { PrefsHelper.putSecureString(context, it, service.apiKey) }
        
        lifecycleScope.launch(Dispatchers.IO) {
            val success = when(service.id) {
                "osrm" -> true
                "ors" -> testOrsKey(service.apiKey)
                "gh" -> testGhKey(service.apiKey)
                "stadia" -> testStadiaKey(service.apiKey)
                "carto" -> service.apiKey.isNotEmpty()
                "open_meteo" -> testOpenMeteo()
                "photon" -> testPhoton()
                "nominatim" -> true
                "esri" -> testEsri()
                else -> true
            }
            
            withContext(Dispatchers.Main) {
                service.status = if (success) ServiceStatus.CONFIGURED else ServiceStatus.ERROR
                apiServiceAdapter.notifyDataSetChanged()
                Toast.makeText(context, if (success) "Kiểm tra thành công!" else "Lỗi cấu hình", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun testOrsKey(key: String): Boolean {
        if (key.isEmpty()) return false
        val url = "https://api.openrouteservice.org/v2/directions/driving-car?api_key=$key&start=8.681495,49.41461&end=8.687872,49.420318"
        return try { OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    private fun testGhKey(key: String): Boolean {
        if (key.isEmpty()) return false
        val url = "https://graphhopper.com/api/1/route?point=51.131,12.414&point=48.224,3.867&vehicle=car&locale=de&key=$key"
        return try { OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    private fun testStadiaKey(key: String): Boolean {
        val url = "https://tiles.stadiamaps.com/tiles/alidade_smooth_dark/0/0/0.png" + if (key.isNotEmpty()) "?api_key=$key" else ""
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

    private fun testEsri(): Boolean {
        val url = "https://services.arcgisonline.com/ArcGIS/rest/services/Canvas/World_Dark_Gray_Base/MapServer/tile/0/0/0"
        return try { OkHttpClient().newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful } } catch (e: Exception) { false }
    }

    private fun setupListeners() {
        binding.btnScan.setOnClickListener {
            checkPermissionsAndScan()
        }

        binding.btnDisconnect.setOnClickListener {
            PrefsHelper.putString(requireContext(), "last_device_mac", "")
            NavigationService.bleManager?.disconnect()?.enqueue()
        }

        binding.btnNotificationAccess?.setOnClickListener {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }

        binding.btnClearLogs.setOnClickListener {
            NavigationRepository.clearLogs()
        }

        binding.btnPauseLog.setOnClickListener {
            isLogPaused = !isLogPaused
            binding.btnPauseLog.text = if (isLogPaused) "Tiếp tục cuộn" else "Tạm dừng cuộn"
            binding.btnPauseLog.setIconResource(if (isLogPaused) R.drawable.ic_play else R.drawable.ic_stop)
            if (!isLogPaused) {
                binding.rvLogs.adapter?.let { (it as LogAdapter).updateLogs(NavigationRepository.logs.value) }
                binding.rvLogs.scrollToPosition(0)
            }
        }

        binding.switchShowLog.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutLogContainer.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.toggleEspMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked && !isSyncingToggle) {
                val cmd: Byte = when (checkedId) {
                    R.id.btnHudMode -> 0x10
                    R.id.btnMapMode -> 0x11
                    R.id.btnStatusMode -> 0x12
                    R.id.btnInfoMode -> 0x13
                    R.id.btnNotifMode -> 0x14
                    else -> 0x10
                }
                NavigationService.bleManager?.sendRemoteCommand(cmd)
            }
        }

        binding.sliderBrightness.addOnChangeListener { _, value, _ ->
            NavigationService.bleManager?.writeSettings("brightness=${value.toInt()}")
        }

        binding.btnRefresh.setOnClickListener {
            NavigationService.bleManager?.sendRemoteCommand(0x20)
        }

        binding.tvAdvanced.setOnClickListener {
            val isVisible = binding.layoutAdvanced.visibility == View.VISIBLE
            binding.layoutAdvanced.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.tvAdvanced.setCompoundDrawablesWithIntrinsicBounds(0, 0, if (isVisible) R.drawable.ic_add else R.drawable.ic_remove, 0)
        }

        binding.btnPing.setOnClickListener {
            NavigationService.bleManager?.sendRemoteCommand(0x30)
        }

        binding.btnRestartEsp.setOnClickListener {
            NavigationService.bleManager?.sendRemoteCommand(0xFF.toByte())
        }

        binding.btnRemoteZoomIn.setOnClickListener {
            NavigationService.bleManager?.writeManualZoom(true)
        }

        binding.btnRemoteZoomOut.setOnClickListener {
            NavigationService.bleManager?.writeManualZoom(false)
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        requestPermissionLauncher.launch(permissions)
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        if (isScanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        binding.scanProgress.visibility = View.VISIBLE
        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                activity?.runOnUiThread { result.device?.let { deviceAdapter.addDevice(it) } }
            }
        }
        scanner.startScan(scanCallback)
        isScanning = true
        Handler(Looper.getMainLooper()).postDelayed({
            scanner.stopScan(scanCallback)
            isScanning = false
            binding.scanProgress.visibility = View.GONE
        }, 10000)
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        val context = requireContext()
        PrefsHelper.putString(context, "last_device_mac", device.address)
        PrefsHelper.addPairedDevice(context, "${device.name ?: "Thiết bị"} (${device.address})")
        setupHistory() 
        context.startForegroundService(Intent(context, NavigationService::class.java).apply { putExtra("CONNECT_MAC", device.address) })
    }

    private fun updateConnectionStatusUI(state: NavigationRepository.BleConnectionState) {
        if (_binding == null) return
        when (state) {
            NavigationRepository.BleConnectionState.Disconnected -> {
                binding.statusText.text = "Mất kết nối"
                binding.statusIndicator.setBackgroundColor(Color.RED)
                binding.btnDisconnect.visibility = View.GONE
            }
            NavigationRepository.BleConnectionState.Connecting -> {
                binding.statusText.text = "Đang kết nối..."
                binding.statusIndicator.setBackgroundColor(Color.YELLOW)
                binding.btnDisconnect.visibility = View.VISIBLE
            }
            NavigationRepository.BleConnectionState.Connected -> {
                binding.statusText.text = "Đã kết nối"
                binding.statusIndicator.setBackgroundColor(Color.CYAN)
                binding.btnDisconnect.visibility = View.VISIBLE
            }
            NavigationRepository.BleConnectionState.Ready -> {
                binding.statusText.text = "Sẵn sàng"
                binding.statusIndicator.setBackgroundColor(Color.GREEN)
                binding.btnDisconnect.visibility = View.VISIBLE
            }
        }
    }

    private fun observeNavigationData() {
        lifecycleScope.launch { NavigationRepository.bleConnectionState.collectLatest { updateConnectionStatusUI(it) } }

        lifecycleScope.launch {
            NavigationRepository.hudPreviewData.collectLatest { hud ->
                if (hud != null) {
                    binding.cardHudPreview.visibility = View.VISIBLE
                    binding.tvHudRoadPreview.text = if (hud.directions.isNotEmpty()) "${hud.title}\n${hud.directions}" else hud.title
                    binding.tvHudDistPreview.text = hud.distance
                    binding.tvHudEtaPreview.text = "ETA: ${hud.eta}"
                    binding.tvHudEtePreview.text = hud.duration
                    if (hud.bitmapIcon != null) binding.ivHudIconPreview.setImageBitmap(hud.bitmapIcon)
                    else binding.ivHudIconPreview.setImageResource(maneuverIconRes(hud.iconIndex))
                } else {
                    binding.cardHudPreview.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            NavigationRepository.deviceStatus.collectLatest { status ->
                binding.layoutDeviceInfo.visibility = if (status.isNotEmpty()) View.VISIBLE else View.GONE
                binding.tvRssi.text = "RSSI: ${status["rssi"] ?: "--"} dBm"
                binding.tvVoltage.text = "Battery: ${status["voltage"] ?: "--"}V"
                binding.tvEspMode.text = "Mode: ${status["mode"] ?: "--"}"
                status["mode"]?.let { mode ->
                    val targetButtonId = when (mode) {
                        "HUD" -> R.id.btnHudMode
                        "MAP" -> R.id.btnMapMode
                        "STATUS" -> R.id.btnStatusMode
                        "INFO" -> R.id.btnInfoMode
                        "NOTIF" -> R.id.btnNotifMode
                        else -> -1
                    }
                    if (targetButtonId != -1 && binding.toggleEspMode.checkedButtonId != targetButtonId) {
                        isSyncingToggle = true
                        binding.toggleEspMode.check(targetButtonId)
                        isSyncingToggle = false
                    }
                }
            }
        }
        
        lifecycleScope.launch {
            NavigationRepository.logs.collectLatest { logs ->
                if (!isLogPaused) {
                    activity?.runOnUiThread {
                        val logAdapter = binding.rvLogs.adapter as? LogAdapter
                        if (logAdapter != null) {
                            logAdapter.updateLogs(logs)
                            if (logs.isNotEmpty()) binding.rvLogs.scrollToPosition(0)
                        } else {
                            binding.rvLogs.adapter = LogAdapter(logs.toMutableList())
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
