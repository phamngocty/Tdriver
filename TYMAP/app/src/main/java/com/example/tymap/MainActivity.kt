package com.example.tymap

import android.os.Bundle
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.viewpager2.widget.ViewPager2
import com.example.tymap.databinding.ActivityMainBinding
import com.example.tymap.ui.MainPagerAdapter

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Luôn ép buộc chế độ đêm ở mức hệ điều hành để đảm bảo tính nhất quán
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupBottomNavigation()
        handleIntent(intent)
        requestBatteryOptimizationExemption()
        
        // Tự động khởi chạy service nếu đã có đủ quyền vị trí
        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, com.example.tymap.service.NavigationService::class.java))
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Update activity intent
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent?.hasExtra("SHARE_TYPE") == true) {
            binding.viewPager.currentItem = 1 // Switch to Map tab
        }
    }

    fun selectTab(position: Int) {
        binding.viewPager.currentItem = position
    }

    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.isUserInputEnabled = false // Disable swiping
        binding.viewPager.offscreenPageLimit = 2 // Keep all tabs in memory

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.bottomNavigation.menu.getItem(position).isChecked = true
            }
        })
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_connection -> binding.viewPager.currentItem = 0
                R.id.nav_map -> binding.viewPager.currentItem = 1
                R.id.nav_settings -> binding.viewPager.currentItem = 2
                R.id.nav_notifications -> binding.viewPager.currentItem = 3
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
}
