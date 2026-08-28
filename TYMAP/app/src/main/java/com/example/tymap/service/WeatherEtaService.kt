package com.example.tymap.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class PointWeather(
    val timeLabel: String,
    val tempC: Int,
    val icon: String,
    val status: String,
    val isRainAlert: Boolean
)

data class EtaFullWeatherResult(
    val travelMinutes: Int,
    val etaTimeStr: String,
    val startPointWeather: PointWeather,
    val etaPointWeather: PointWeather,
    val etaPlus1hPointWeather: PointWeather
)

object WeatherEtaService {
    private val client = OkHttpClient()

    /**
     * Tra cứu đầy đủ 3 mốc thời tiết như Web Dashboard NAS:
     * 1. Lúc xuất phát (Start Time)
     * 2. Lúc đến nơi (ETA Time)
     * 3. 1 Giờ sau khi đến nơi (ETA + 1h)
     */
    suspend fun checkFullRouteWeather(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        durationSeconds: Double
    ): EtaFullWeatherResult? = withContext(Dispatchers.IO) {
        try {
            val travelMinutes = Math.round(durationSeconds / 60.0).toInt()
            val now = Date()
            val etaMillis = now.time + (durationSeconds * 1000).toLong()
            val etaTime = Date(etaMillis)
            val etaPlus1hTime = Date(etaMillis + 3600 * 1000L)

            val hmSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val isoHourSdf = SimpleDateFormat("yyyy-MM-dd'T'HH:00", Locale.US)

            val urlStart = "https://api.open-meteo.com/v1/forecast?latitude=$startLat&longitude=$startLng&current=temperature_2m,weather_code&timezone=auto"
            val urlEnd = "https://api.open-meteo.com/v1/forecast?latitude=$destLat&longitude=$destLng&hourly=temperature_2m,precipitation_probability,precipitation,weather_code&forecast_days=2&timezone=auto"

            var startWeatherPoint = PointWeather(
                timeLabel = hmSdf.format(now),
                tempC = 28,
                icon = "☀️",
                status = "Tạnh ráo",
                isRainAlert = false
            )

            // 1. Lấy thời tiết điểm xuất phát
            try {
                val reqStart = Request.Builder().url(urlStart).header("User-Agent", "TYMAP/1.0").build()
                client.newCall(reqStart).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: ""
                        val json = JSONObject(body)
                        val curr = json.optJSONObject("current")
                        if (curr != null) {
                            val temp = curr.optDouble("temperature_2m", 28.0).toInt()
                            val code = curr.optInt("weather_code", 0)
                            val interp = interpretWeather(code, 0.0, 0, temp)
                            startWeatherPoint = PointWeather(
                                timeLabel = hmSdf.format(now),
                                tempC = temp,
                                icon = interp.icon,
                                status = interp.status,
                                isRainAlert = interp.isRainAlert
                            )
                        }
                    }
                }
            } catch (e: Exception) { }

            // 2. Lấy thời tiết theo giờ tại điểm đến
            val reqEnd = Request.Builder().url(urlEnd).header("User-Agent", "TYMAP/1.0").build()
            client.newCall(reqEnd).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val hourly = json.optJSONObject("hourly") ?: return@withContext null

                val times = hourly.optJSONArray("time") ?: return@withContext null
                val temps = hourly.optJSONArray("temperature_2m")
                val rainProbs = hourly.optJSONArray("precipitation_probability")
                val rainMms = hourly.optJSONArray("precipitation")
                val codes = hourly.optJSONArray("weather_code")

                val etaIso = isoHourSdf.format(etaTime)
                val eta1Iso = isoHourSdf.format(etaPlus1hTime)

                var etaIdx = 0
                var eta1Idx = 1
                for (i in 0 until times.length()) {
                    if (times.getString(i) == etaIso) etaIdx = i
                    if (times.getString(i) == eta1Iso) eta1Idx = i
                }

                val etaInterp = interpretWeather(
                    code = codes?.optInt(etaIdx, 0) ?: 0,
                    precip = rainMms?.optDouble(etaIdx, 0.0) ?: 0.0,
                    prob = rainProbs?.optInt(etaIdx, 0) ?: 0,
                    temp = temps?.optDouble(etaIdx, 28.0)?.toInt() ?: 28
                )

                val eta1Interp = interpretWeather(
                    code = codes?.optInt(eta1Idx, 0) ?: 0,
                    precip = rainMms?.optDouble(eta1Idx, 0.0) ?: 0.0,
                    prob = rainProbs?.optInt(eta1Idx, 0) ?: 0,
                    temp = temps?.optDouble(eta1Idx, 27.0)?.toInt() ?: 27
                )

                return@withContext EtaFullWeatherResult(
                    travelMinutes = travelMinutes,
                    etaTimeStr = hmSdf.format(etaTime),
                    startPointWeather = startWeatherPoint,
                    etaPointWeather = PointWeather(
                        timeLabel = hmSdf.format(etaTime),
                        tempC = etaInterp.temp,
                        icon = etaInterp.icon,
                        status = etaInterp.status,
                        isRainAlert = etaInterp.isRainAlert
                    ),
                    etaPlus1hPointWeather = PointWeather(
                        timeLabel = hmSdf.format(etaPlus1hTime),
                        tempC = eta1Interp.temp,
                        icon = eta1Interp.icon,
                        status = eta1Interp.status,
                        isRainAlert = eta1Interp.isRainAlert
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("WeatherEtaService", "Error checking full weather: ${e.message}")
            null
        }
    }

    private data class InterpResult(val icon: String, val status: String, val temp: Int, val isRainAlert: Boolean)

    private fun interpretWeather(code: Int, precip: Double, prob: Int, temp: Int): InterpResult {
        var status = "Tạnh ráo"
        var isRain = false
        var icon = "☀️"

        if (code in 51..55) {
            status = "Mưa phùn ($prob% mưa)"
            isRain = true
            icon = "🌦️"
        } else if (code in 61..65) {
            status = if (precip > 0) "Mưa rào ${precip}mm ($prob%)" else "Mưa rào ($prob% mưa)"
            isRain = true
            icon = "🌧️"
        } else if (code in 80..82) {
            status = "Mưa dông lớn ($prob%)"
            isRain = true
            icon = "⛈️"
        } else if (code >= 95) {
            status = "Dông sét nguy hiểm ($prob%)"
            isRain = true
            icon = "⚡"
        } else if (prob >= 60) {
            status = "Khả năng mưa cao ($prob%)"
            isRain = true
            icon = "🌧️"
        } else if (code in 1..3) {
            status = if (prob > 20) "Nhiều mây ($prob% mưa)" else "Nhiều mây"
            icon = "⛅"
        }

        return InterpResult(icon, status, temp, isRain)
    }
}
