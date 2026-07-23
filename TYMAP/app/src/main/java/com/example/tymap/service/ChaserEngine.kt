package com.example.tymap.service

import android.location.Location
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Intelligent Chaser Engine (ICE)
 * Monitors user location against current polyline.
 * Triggers route deviation event when user goes off-route (> 15m default),
 * respecting strict 2s debounce and cooldown parameters.
 */
class ChaserEngine(
    private var offRouteThresholdMeters: Float = 15f,
    private var cooldownMs: Long = 2000L
) {
    private var lastCheckTime: Long = 0L
    private var lastDeviationTime: Long = 0L

    var onRouteDeviationListener: ((Location) -> Unit)? = null

    fun setOffRouteThreshold(meters: Float) {
        this.offRouteThresholdMeters = meters
    }

    fun setCooldownMs(ms: Long) {
        this.cooldownMs = ms
    }

    /**
     * Evaluates whether current location deviates from the given polyline.
     * Computes distance on Dispatchers.Default to ensure Main Thread performance.
     */
    suspend fun checkDeviation(
        location: Location,
        polyline: List<Pair<Double, Double>>
    ): Boolean = withContext(Dispatchers.Default) {
        if (polyline.isEmpty()) return@withContext false

        val now = System.currentTimeMillis()
        if (now - lastCheckTime < 2000L) {
            return@withContext false
        }
        lastCheckTime = now

        val distanceToRoute = computeDistanceToPolyline(location, polyline)
        if (distanceToRoute > offRouteThresholdMeters) {
            if (now - lastDeviationTime >= cooldownMs) {
                lastDeviationTime = now
                onRouteDeviationListener?.invoke(location)
                return@withContext true
            }
        }
        return@withContext false
    }

    private fun computeDistanceToPolyline(
        location: Location,
        polyline: List<Pair<Double, Double>>
    ): Double {
        val results = FloatArray(1)
        var minDistance = Double.MAX_VALUE

        for (pt in polyline) {
            Location.distanceBetween(
                location.latitude, location.longitude,
                pt.first, pt.second,
                results
            )
            val d = results[0].toDouble()
            if (d < minDistance) {
                minDistance = d
                if (minDistance < 2.0) break
            }
        }
        return minDistance
    }
}
