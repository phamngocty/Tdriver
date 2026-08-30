package com.example.tymap

import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import androidx.lifecycle.lifecycleScope
import com.example.tymap.databinding.ActivityMainBinding
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.service.NavigationService
import com.example.tymap.ui.MainPagerAdapter
import com.example.tymap.utils.PrefsHelper
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var oledDeviceConnected = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startNavigationService()
            checkBackgroundLocation()
        } else {
            Toast.makeText(this, "Vui lòng cấp đủ quyền để App hoạt động ổn định", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Luôn ép buộc chế độ đêm ở mức hệ điều hành để đảm bảo tính nhất quán
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupBottomNavigation()
        PrefsHelper.putBoolean(this, "render_tab_unlocked", false)
        updateRenderTabVisibility()
        observeOledConnection()
        observeKeepScreenOn()
        handleIntent(intent)
        requestBatteryOptimizationExemption()
        
        checkAndRequestPermissions()
    }

    // Tự động giữ màn hình luôn sáng khi đang dẫn đường (nếu bật tùy chọn)
    private fun observeKeepScreenOn() {
        lifecycleScope.launch {
            NavigationRepository.navigationState.collect { running ->
                val keepScreenOn = PrefsHelper.getBoolean(this@MainActivity, "keep_screen_on", true)
                if (running && keepScreenOn) {
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    // Tự hiện tab Render (ESP32 OLED) khi đang kết nối thiết bị OLED qua BLE.
    private fun observeOledConnection() {
        lifecycleScope.launch {
            combine(
                NavigationRepository.bleConnectionState,
                NavigationRepository.deviceStatus
            ) { state, status ->
                val connected = state == NavigationRepository.BleConnectionState.Connected ||
                    state == NavigationRepository.BleConnectionState.Ready
                val display = status["display"] ?: ""
                connected && (display.contains("OLED", ignoreCase = true) ||
                    display.contains("SSD1306", ignoreCase = true) ||
                    display.contains("SH1106", ignoreCase = true) ||
                    display.contains("TYMAP", ignoreCase = true))
            }.collect { isOled ->
                oledDeviceConnected = isOled
                updateRenderTabVisibility()
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            // Below Android 12, generic Bluetooth permissions are enough, 
            // but we usually have them in Manifest.
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            startNavigationService()
            checkBackgroundLocation()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun checkBackgroundLocation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Quyền truy cập vị trí nền")
                    .setMessage("Để dẫn đường và cập nhật tốc độ khi tắt màn hình, vui lòng chọn 'Luôn cho phép' (Allow all the time) trong cài đặt vị trí.")
                    .setPositiveButton("Cài đặt") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Hủy", null)
                    .show()
            }
        }
    }

    private fun startNavigationService() {
        val intent = Intent(this, NavigationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update activity intent
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.hasExtra("SHARE_TYPE") == true) {
            binding.viewPager.currentItem = 0 // Switch to Map tab
        }
    }

    fun selectTab(position: Int) {
        binding.viewPager.currentItem = position
    }

    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.isUserInputEnabled = false // Disable swiping
        binding.viewPager.offscreenPageLimit = 3 // Keep all tabs in memory (4 fragments)

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNavigation.setSelectedTab(position, animate = true)
            }
        })
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { position ->
            binding.viewPager.currentItem = position
        }

        val density = resources.displayMetrics.density
        val baseMarginBottom = (12 * density).toInt()
        val bubbleOvershoot = (16 * density).toInt()

        // Single source of truth: the vertical space the floating Liquid nav
        // (capsule + raised bubble) occupies. Reserved as bottom padding on the
        // ViewPager so every fragment's content (incl. the map's operation
        // buttons and bottom sheet) is kept strictly above the nav bar.
        fun applyBottomClearance() {
            val navHeight = binding.bottomNavigation.height
            if (navHeight <= 0) return // not laid out yet; retried on insets/layout
            val params = binding.bottomNavigation.layoutParams as ViewGroup.MarginLayoutParams
            val navOccupiedSpace = navHeight + params.bottomMargin
            binding.viewPager.setPadding(0, 0, 0, navOccupiedSpace + bubbleOvershoot)
        }

        // Keep the capsule above the system navigation bar and recompute the
        // content clearance whenever the insets change.
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavigation) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            params.bottomMargin = baseMarginBottom + insets.bottom
            view.layoutParams = params
            view.post { applyBottomClearance() }
            windowInsets
        }

        // Cover the first layout pass and late inset dispatch.
        binding.bottomNavigation.post { applyBottomClearance() }
    }

    private fun requestBatteryOptimizationExemption() {
        val packageName = packageName
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent().apply {
                action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                data = Uri.parse("package:$packageName")
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Vui lòng tắt tối ưu pin cho TYMAP trong cài đặt", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun updateRenderTabVisibility() {
        val isUnlocked = PrefsHelper.getBoolean(this, "render_tab_unlocked", false)
        val visible = isUnlocked || oledDeviceConnected
        binding.bottomNavigation.setRenderTabVisible(visible)
        // Tránh rơi vào màn hình trống nếu tab Render bị ẩn khi đang mở.
        if (!visible && binding.viewPager.currentItem == 3) {
            binding.viewPager.currentItem = 0
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PrefsHelper.putBoolean(this, "render_tab_unlocked", false)
    }
}
