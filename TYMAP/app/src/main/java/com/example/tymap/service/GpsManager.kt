package com.example.tymap.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.example.tymap.repository.NavigationRepository

class GpsManager(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var isUpdating = false
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLocation: Location? = null

    var onSpeedUpdated: ((Int) -> Unit)? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val freshLocation = Location(location)
            
            // Tính toán tốc độ chuẩn xác: nếu location.hasSpeed() bị false hoặc bằng 0 khi xe đang di chuyển,
            // tự động tính qua delta khoảng cách / delta thời gian
            var speedKmh = if (freshLocation.hasSpeed() && freshLocation.speed > 0f) {
                (freshLocation.speed * 3.6f).toInt()
            } else {
                val prev = lastLocation
                if (prev != null && freshLocation.time > prev.time) {
                    val dist = freshLocation.distanceTo(prev)
                    val dtSec = (freshLocation.time - prev.time) / 1000f
                    if (dtSec in 0.2f..10.0f) {
                        ((dist / dtSec) * 3.6f).toInt()
                    } else 0
                } else 0
            }
            speedKmh = speedKmh.coerceIn(0, 250)
            lastLocation = freshLocation
            freshLocation.speed = speedKmh / 3.6f

            NavigationRepository.updateLocation(freshLocation, speedKmh)

            // Gửi tốc độ tức thì qua callback (không delay, không phụ thuộc UI thread)
            onSpeedUpdated?.invoke(speedKmh)
            
            // Tích hợp kiểm tra Cảnh báo Giao thông Offline trên mảng RAM Cache mỗi khi có GPS mới
            TrafficWarningManager.checkGpsLocation(context, freshLocation, NavigationService.bleManager)
            
            android.util.Log.d("GpsManager", "New Location: ${location.latitude}, ${location.longitude} (Speed: ${speedKmh} km/h, Acc: ${location.accuracy}m, Provider: ${location.provider})")
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        
        override fun onProviderEnabled(provider: String) {
            NavigationRepository.addLog("GPS Provider enabled: $provider")
            restartLocationUpdates()
        }
        
        override fun onProviderDisabled(provider: String) {
            NavigationRepository.addLog("GPS Provider disabled: $provider")
        }
    }

    /**
     * Determines whether one Location reading is better than the current Location fix
     */

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            NavigationRepository.addLog("GPS Waiting for permissions...")
            return
        }

        try {
            // Kích hoạt WakeLock riêng cho GPS để giữ CPU thức khi tắt màn hình
            if (wakeLock == null) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TYMAP:GpsManagerWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(24 * 60 * 60 * 1000L)
            }

            if (handlerThread == null) {
                handlerThread = HandlerThread("GpsHandlerThread").apply { start() }
                handler = Handler(handlerThread!!.looper)
            }

            registerUpdates()
            isUpdating = true
            
            // Lấy vị trí gần nhất ngay lập tức
            val lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val bestLocation = if (lastGps != null && lastNet != null) {
                if (lastGps.accuracy < lastNet.accuracy) lastGps else lastNet
            } else lastGps ?: lastNet
            
            bestLocation?.let { 
                val fresh = Location(it)
                lastLocation = fresh
                val spd = (fresh.speed * 3.6f).toInt().coerceIn(0, 250)
                NavigationRepository.updateLocation(fresh, spd)
                onSpeedUpdated?.invoke(spd)
            }
            
        } catch (e: Exception) {
            NavigationRepository.addLog("GPS Start Error: ${e.message}")
            stopLocationUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerUpdates() {
        handler?.post {
            try {
                locationManager.removeUpdates(locationListener)
                
                // Đăng ký FUSED_PROVIDER trên Android 12+ (API 31+) để tối ưu định vị nền
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (locationManager.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                        try {
                            locationManager.requestLocationUpdates(
                                LocationManager.FUSED_PROVIDER,
                                500L,
                                0f,
                                locationListener,
                                handlerThread!!.looper
                            )
                            android.util.Log.d("GpsManager", "FUSED_PROVIDER registered successfully")
                        } catch (e: Exception) {
                            android.util.Log.w("GpsManager", "Could not register FUSED_PROVIDER: ${e.message}")
                        }
                    }
                }

                // Sử dụng 500ms và minDistance = 0f để nhận vị trí dày hơn nếu phần cứng hỗ trợ
                if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        500L, 
                        0f,
                        locationListener,
                        handlerThread!!.looper
                    )
                }

                // Network provider chỉ dùng làm fallback nếu GPS không có tín hiệu
                if (locationManager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        5000L, // Tăng thời gian lấy mẫu mạng để tránh nhảy vị trí
                        0f,
                        locationListener,
                        handlerThread!!.looper
                    )
                }
                
                android.util.Log.d("GpsManager", "Location updates registered successfully with minDistance=0f")
            } catch (e: Exception) {
                android.util.Log.e("GpsManager", "Failed to register updates", e)
            }
        }
    }

    private fun restartLocationUpdates() {
        if (isUpdating) {
            registerUpdates()
        }
    }

    fun stopLocationUpdates() {
        try {
            locationManager.removeUpdates(locationListener)
            handlerThread?.quitSafely()
            handlerThread = null
            handler = null
            isUpdating = false
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            android.util.Log.e("GpsManager", "Error stopping GPS", e)
        }
    }
}

