package com.example.tymap.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import com.example.tymap.repository.NavigationRepository

class GpsManager(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var isUpdating = false
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Revert back to simple update to ensure responsiveness
            // NavigationRepository already updated to accept all valid locations
            val freshLocation = Location(location)
            NavigationRepository.updateLocation(freshLocation)
            
            // Tích hợp kiểm tra Cảnh báo Giao thông Offline trên mảng RAM Cache mỗi khi có GPS mới
            TrafficWarningManager.checkGpsLocation(context, freshLocation, NavigationService.bleManager)
            
            android.util.Log.d("GpsManager", "New Location: ${location.latitude}, ${location.longitude} (Acc: ${location.accuracy}m, Provider: ${location.provider})")
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
                NavigationRepository.updateLocation(Location(it))
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
                
                // Sử dụng 1000ms (1 giây) và minDistance = 0f (không có ngưỡng di chuyển để cập nhật liên tục)
                if (locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        1000L, 
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
        } catch (e: Exception) {
            android.util.Log.e("GpsManager", "Error stopping GPS", e)
        }
    }
}
