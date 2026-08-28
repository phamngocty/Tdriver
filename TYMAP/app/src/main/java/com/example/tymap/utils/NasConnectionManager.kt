package com.example.tymap.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.OkHttpClient
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * NasConnectionManager
 * Centralized Circuit Breaker and Smart Network Dispatcher for TYMAP.
 * 
 * Objectives:
 * 1. Fast-fail NAS connections within 1000ms - 1500ms when user is outside LAN (4G/LTE / remote Wi-Fi).
 * 2. Circuit Breaker: When NAS connection fails, cache offline state for 25s so subsequent calls
 *    (Search, Routing, Geocoding, Speed Limit, Traffic Warnings) SKIP NAS instantly (0ms) and use Cloud APIs.
 * 3. Support dual-mode connectivity:
 *    - LAN Mode: Direct IP access (e.g. 192.168.1.114:8989)
 *    - Remote 4G/5G Mode: Secure HTTPS subdomain routing via DuckDNS + Nginx Proxy Manager (e.g. https://route.nas152.duckdns.org)
 * 4. Immediately recover when NAS responds successfully.
 */
object NasConnectionManager {
    private const val TAG = "NasConnectionManager"

    // Default Commercial Host (Auto zero-config for 4G/5G users)
    const val DEFAULT_PUBLIC_DOMAIN = "nas152.duckdns.org"
    const val DEFAULT_LAN_IP = "192.168.1.114"

    // Circuit Breaker cooldown (25 seconds)
    private const val FAILURE_COOLDOWN_MS = 25000L

    // Fast timeouts for local NAS & low-latency DuckDNS
    private const val NAS_CONNECT_TIMEOUT_MS = 1200L
    private const val NAS_READ_TIMEOUT_MS = 2500L

    // Responsive timeouts for public cloud APIs
    private const val PUBLIC_CONNECT_TIMEOUT_MS = 3500L
    private const val PUBLIC_READ_TIMEOUT_MS = 4000L

    // Search autocomplete fast timeouts
    private const val SEARCH_CONNECT_TIMEOUT_MS = 1500L
    private const val SEARCH_READ_TIMEOUT_MS = 2000L

    private val isNasReachable = AtomicBoolean(true)
    private val lastFailureTime = AtomicLong(0L)

    val nasHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(NAS_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(NAS_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    val publicHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(PUBLIC_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PUBLIC_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val fastSearchHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(SEARCH_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(SEARCH_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Checks whether NAS should even be attempted.
     * Returns false immediately (0ms) if NAS failed within the cooldown period or if device is completely offline.
     */
    fun isNasPotentiallyAvailable(context: Context): Boolean {
        val now = System.currentTimeMillis()
        val lastFail = lastFailureTime.get()

        if (now - lastFail < FAILURE_COOLDOWN_MS) {
            return false
        }

        // If not connected to Wi-Fi and the NAS IP is a private RFC1918 LAN IP, NAS is unreachable on mobile data
        val nasIp = getNasIp(context)
        if (isPrivateLanIp(nasIp)) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(network)
            val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

            // On purely cellular mobile data, private LAN IP is unreachable unless VPN is active
            if (!isWifi && !isVpn) {
                return false
            }
        }

        return true
    }

    /**
     * Get configured NAS IP address or Public Domain (fallback: nas152.duckdns.org)
     */
    fun getNasIp(context: Context): String {
        return PrefsHelper.getString(context, "nas_ip", "").ifEmpty {
            PrefsHelper.getString(context, "nas_server_ip", DEFAULT_PUBLIC_DOMAIN)
        }.ifEmpty { DEFAULT_PUBLIC_DOMAIN }
    }

    /**
     * Base URL for GraphHopper (Port 8989 or https://route.domain)
     */
    fun getGraphHopperBaseUrl(context: Context): String {
        val host = getNasIp(context).trim()
        return if (isPrivateLanIp(host)) {
            "http://$host:8989"
        } else {
            if (host.startsWith("http://") || host.startsWith("https://")) {
                host.trimEnd('/')
            } else if (host.startsWith("route.")) {
                "https://$host"
            } else {
                "https://route.$host"
            }
        }
    }

    /**
     * Base URL for Photon Autocomplete Search (Port 2322 or https://search.domain)
     */
    fun getPhotonBaseUrl(context: Context): String {
        val host = getNasIp(context).trim()
        return if (isPrivateLanIp(host)) {
            "http://$host:2322"
        } else {
            if (host.startsWith("http://") || host.startsWith("https://")) {
                host.trimEnd('/')
            } else if (host.startsWith("search.")) {
                "https://$host"
            } else {
                "https://search.$host"
            }
        }
    }

    /**
     * Base URL for Nominatim Geocoding (Port 8081 or https://geo.domain)
     */
    fun getNominatimBaseUrl(context: Context): String {
        val host = getNasIp(context).trim()
        return if (isPrivateLanIp(host)) {
            "http://$host:8081"
        } else {
            if (host.startsWith("http://") || host.startsWith("https://")) {
                host.trimEnd('/')
            } else if (host.startsWith("geo.")) {
                "https://$host"
            } else {
                "https://geo.$host"
            }
        }
    }

    /**
     * Base URL for Fusion Engine Alerts (Port 8088 or https://alert.domain)
     */
    fun getFusionEngineBaseUrl(context: Context): String {
        val host = getNasIp(context).trim()
        return if (isPrivateLanIp(host)) {
            "http://$host:8088"
        } else {
            if (host.startsWith("http://") || host.startsWith("https://")) {
                host.trimEnd('/')
            } else if (host.startsWith("alert.")) {
                "https://$host"
            } else {
                "https://alert.$host"
            }
        }
    }

    /**
     * Helper to check whether an endpoint belongs to NAS (LAN or DuckDNS/Public domain)
     */
    fun isNasEndpoint(url: String): Boolean {
        return url.contains(":8989") ||
               url.contains(":2322") ||
               url.contains(":8081") ||
               url.contains(":8088") ||
               url.contains("duckdns.org") ||
               url.contains("192.168.")
    }

    /**
     * Determines if an IP is a private LAN address (RFC1918)
     */
    fun isPrivateLanIp(ip: String): Boolean {
        val clean = ip.trim()
        return clean.startsWith("192.168.") ||
               clean.startsWith("10.") ||
               clean.startsWith("127.") ||
               clean.startsWith("172.16.") ||
               clean.startsWith("172.17.") ||
               clean.startsWith("172.18.") ||
               clean.startsWith("172.19.") ||
               clean.startsWith("172.2") ||
               clean.startsWith("172.30.") ||
               clean.startsWith("172.31.") ||
               clean == "localhost"
    }

    /**
     * Mark NAS as failed and activate circuit breaker cooldown
     */
    fun markNasFailed(reason: String? = null) {
        val wasReachable = isNasReachable.getAndSet(false)
        lastFailureTime.set(System.currentTimeMillis())
        if (wasReachable) {
            Log.w(TAG, "⚡ [Circuit Breaker] NAS marked OFFLINE (cooldown 25s). Fallbacks will be direct & instant. Reason: $reason")
        }
    }

    /**
     * Mark NAS as healthy / online
     */
    fun markNasSuccess() {
        val wasOffline = !isNasReachable.getAndSet(true)
        lastFailureTime.set(0L)
        if (wasOffline) {
            Log.i(TAG, "✅ [Circuit Breaker] NAS marked ONLINE & reachable.")
        }
    }

    /**
     * Helper to classify network errors that should trip the NAS circuit breaker immediately
     */
    fun isConnectionFailure(e: Throwable): Boolean {
        return e is SocketTimeoutException ||
               e is ConnectException ||
               e is NoRouteToHostException ||
               e is UnknownHostException
    }
}
