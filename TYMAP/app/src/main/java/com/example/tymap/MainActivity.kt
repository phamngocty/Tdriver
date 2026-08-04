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
import androidx.core.content.ContextCompat
import androidx.viewpager2.widget.ViewPager2
import com.example.tymap.databinding.ActivityMainBinding
import com.example.tymap.ui.MainPagerAdapter

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

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
        com.example.tymap.utils.PrefsHelper.putBoolean(this, "render_tab_unlocked", false)
        updateRenderTabVisibility()
        handleIntent(intent)
        requestBatteryOptimizationExemption()
        
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
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
        val intent = Intent(this, com.example.tymap.service.NavigationService::class.java)
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
                binding.bottomNavigation.menu.getItem(position).isChecked = true
            }
        })
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_map -> binding.viewPager.currentItem = 0
                R.id.nav_settings -> binding.viewPager.currentItem = 1
                R.id.nav_notifications -> binding.viewPager.currentItem = 2
                R.id.nav_render -> binding.viewPager.currentItem = 3
            }
            true
        }
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
        val isUnlocked = com.example.tymap.utils.PrefsHelper.getBoolean(this, "render_tab_unlocked", false)
        binding.bottomNavigation.menu.findItem(R.id.nav_render)?.isVisible = isUnlocked
    }

    override fun onDestroy() {
        super.onDestroy()
        com.example.tymap.utils.PrefsHelper.putBoolean(this, "render_tab_unlocked", false)
    }
}
