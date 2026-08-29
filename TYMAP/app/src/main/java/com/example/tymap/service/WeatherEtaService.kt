package com.example.tymap.service

import android.content.Context
import com.example.tymap.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

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
    val etaPlus1hPointWeather: PointWeather,
    val provider: String = "Open-Meteo (ECMWF/JMA)"
)

object WeatherEtaService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Tra cứu đầy đủ 3 mốc thời tiết:
     * 1. Lúc xuất phát (Start Time)
     * 2. Lúc đến nơi (ETA Time)
     * 3. 1 Giờ sau khi đến nơi (ETA + 1h)
     *
     * Ưu tiên 1: WeatherAPI.com (nếu có key, độ chính xác hàng đầu VN, hỗ trợ tiếng Việt)
     * Ưu tiên 2 (Fallback): Open-Meteo High Precision Multi-Model (ECMWF Châu Âu & JMA Nhật Bản)
     */
    suspend fun checkFullRouteWeather(
        context: Context? = null,
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        durationSeconds: Double
    ): EtaFullWeatherResult? = withContext(Dispatchers.IO) {
        val weatherApiKey = if (context != null) {
            PrefsHelper.getSecureString(context, "api_key_weatherapi", "").trim()
        } else ""

        // 1. Thử WeatherAPI.com nếu người dùng đã cấu hình Key
        if (weatherApiKey.isNotEmpty()) {
            val weatherApiResult = queryWeatherApi(weatherApiKey, startLat, startLng, destLat, destLng, durationSeconds)
            if (weatherApiResult != null) {
                return@withContext weatherApiResult
            }
            android.util.Log.w("WeatherEtaService", "WeatherAPI.com query failed, falling back to Open-Meteo ECMWF/JMA")
        }

        // 2. Fallback sang Open-Meteo với bộ mô hình độ chính xác cao ECMWF / JMA
        return@withContext queryOpenMeteoHighPrecision(startLat, startLng, destLat, destLng, durationSeconds)
    }

    /**
     * Truy vấn thời tiết qua WeatherAPI.com (Chính xác cao cho Việt Nam)
     */
    private fun queryWeatherApi(
        apiKey: String,
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        durationSeconds: Double
    ): EtaFullWeatherResult? {
        try {
            val travelMinutes = Math.round(durationSeconds / 60.0).toInt().coerceAtLeast(1)
            val now = Date()
            val nowSec = now.time / 1000L
            val etaSec = nowSec + durationSeconds.toLong()
            val etaPlus1hSec = etaSec + 3600L

            val hmSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val etaTimeStr = hmSdf.format(Date(etaSec * 1000L))
            val etaPlus1hTimeStr = hmSdf.format(Date(etaPlus1hSec * 1000L))

            // 1. Lấy thời tiết xuất phát
            val urlStart = "https://api.weatherapi.com/v1/current.json?key=$apiKey&q=$startLat,$startLng&lang=vi"
            val reqStart = Request.Builder().url(urlStart).build()
            var startWeather = PointWeather(hmSdf.format(now), 28, "☀️", "Nắng ráo", false)

            client.newCall(reqStart).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string() ?: "")
                    val current = json.optJSONObject("current")
                    if (current != null) {
                        val temp = current.optDouble("temp_c", 28.0).toInt()
                        val isDay = current.optInt("is_day", 1)
                        val precipMm = current.optDouble("precip_mm", 0.0)
                        val condObj = current.optJSONObject("condition")
                        val code = condObj?.optInt("code", 1000) ?: 1000
                        val text = condObj?.optString("text", "") ?: ""

                        val interp = interpretWeatherApiCode(code, text, precipMm, 0, temp, isDay)
                        startWeather = PointWeather(hmSdf.format(now), temp, interp.icon, interp.status, interp.isRainAlert)
                    }
                }
            }

            // 2. Lấy thời tiết dự báo theo giờ tại đích đến (forecast 2 ngày)
            val urlEnd = "https://api.weatherapi.com/v1/forecast.json?key=$apiKey&q=$destLat,$destLng&days=2&aqi=no&alerts=no&lang=vi"
            val reqEnd = Request.Builder().url(urlEnd).build()
            client.newCall(reqEnd).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string() ?: "")
                val forecast = json.optJSONObject("forecast") ?: return null
                val days = forecast.optJSONArray("forecastday") ?: return null

                // Gộp tất cả các giờ từ 2 ngày
                val allHours = mutableListOf<JSONObject>()
                for (d in 0 until days.length()) {
                    val dayObj = days.optJSONObject(d)
                    val hours = dayObj?.optJSONArray("hour")
                    if (hours != null) {
                        for (h in 0 until hours.length()) {
                            hours.optJSONObject(h)?.let { allHours.add(it) }
                        }
                    }
                }

                if (allHours.isEmpty()) return null

                var etaHourObj = allHours[0]
                var eta1HourObj = allHours[0]
                var minDiffEta = Long.MAX_VALUE
                var minDiffEta1 = Long.MAX_VALUE

                for (h in allHours) {
                    val epoch = h.optLong("time_epoch", 0L)
                    val diffEta = Math.abs(epoch - etaSec)
                    if (diffEta < minDiffEta) {
                        minDiffEta = diffEta
                        etaHourObj = h
                    }
                    val diffEta1 = Math.abs(epoch - etaPlus1hSec)
                    if (diffEta1 < minDiffEta1) {
                        minDiffEta1 = diffEta1
                        eta1HourObj = h
                    }
                }

                val etaTemp = etaHourObj.optDouble("temp_c", 28.0).toInt()
                val etaRainProb = etaHourObj.optInt("chance_of_rain", 0)
                val etaPrecip = etaHourObj.optDouble("precip_mm", 0.0)
                val etaIsDay = etaHourObj.optInt("is_day", 1)
                val etaCond = etaHourObj.optJSONObject("condition")
                val etaInterp = interpretWeatherApiCode(
                    etaCond?.optInt("code", 1000) ?: 1000,
                    etaCond?.optString("text", "") ?: "",
                    etaPrecip, etaRainProb, etaTemp, etaIsDay
                )

                val eta1Temp = eta1HourObj.optDouble("temp_c", 27.0).toInt()
                val eta1RainProb = eta1HourObj.optInt("chance_of_rain", 0)
                val eta1Precip = eta1HourObj.optDouble("precip_mm", 0.0)
                val eta1IsDay = eta1HourObj.optInt("is_day", 1)
                val eta1Cond = eta1HourObj.optJSONObject("condition")
                val eta1Interp = interpretWeatherApiCode(
                    eta1Cond?.optInt("code", 1000) ?: 1000,
                    eta1Cond?.optString("text", "") ?: "",
                    eta1Precip, eta1RainProb, eta1Temp, eta1IsDay
                )

                return EtaFullWeatherResult(
                    travelMinutes = travelMinutes,
                    etaTimeStr = etaTimeStr,
                    startPointWeather = startWeather,
                    etaPointWeather = PointWeather(etaTimeStr, etaInterp.temp, etaInterp.icon, etaInterp.status, etaInterp.isRainAlert),
                    etaPlus1hPointWeather = PointWeather(etaPlus1hTimeStr, eta1Interp.temp, eta1Interp.icon, eta1Interp.status, eta1Interp.isRainAlert),
                    provider = "WeatherAPI.com"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("WeatherEtaService", "WeatherAPI query error: ${e.message}")
            return null
        }
    }

    /**
     * Truy vấn Open-Meteo với các mô hình dự báo chính xác cao (ECMWF & JMA cho Châu Á)
     */
    private fun queryOpenMeteoHighPrecision(
        startLat: Double, startLng: Double,
        destLat: Double, destLng: Double,
        durationSeconds: Double
    ): EtaFullWeatherResult? {
        try {
            val travelMinutes = Math.round(durationSeconds / 60.0).toInt().coerceAtLeast(1)
            val now = Date()
            val nowSec = now.time / 1000L
            val etaSec = nowSec + durationSeconds.toLong()
            val etaPlus1hSec = etaSec + 3600L

            val hmSdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val etaTimeStr = hmSdf.format(Date(etaSec * 1000L))
            val etaPlus1hTimeStr = hmSdf.format(Date(etaPlus1hSec * 1000L))

            val urlStart = "https://api.open-meteo.com/v1/forecast?latitude=$startLat&longitude=$startLng&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m&models=best_match,ecmwf_ifs025,jma_gsm&timeformat=unixtime"
            val urlEnd = "https://api.open-meteo.com/v1/forecast?latitude=$destLat&longitude=$destLng&current=temperature_2m,is_day,precipitation,weather_code&hourly=temperature_2m,precipitation_probability,precipitation,weather_code,is_day&models=best_match,ecmwf_ifs025,jma_gsm&forecast_days=2&timeformat=unixtime"

            var startWeatherPoint = PointWeather(
                timeLabel = hmSdf.format(now),
                tempC = 28,
                icon = "☀️",
                status = "Tạnh ráo",
                isRainAlert = false
            )

            // 1. Lấy thời tiết hiện tại tại điểm xuất phát
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
                            val precip = curr.optDouble("precipitation", 0.0)
                            val isDay = curr.optInt("is_day", 1)
                            val interp = interpretOpenMeteoCode(code, precip, if (precip > 0.1) 80 else 0, temp, isDay)
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
            } catch (e: Exception) {
                android.util.Log.w("WeatherEtaService", "Start weather fetch warning: ${e.message}")
            }

            // 2. Lấy thời tiết theo giờ tại điểm đến
            val reqEnd = Request.Builder().url(urlEnd).header("User-Agent", "TYMAP/1.0").build()
            client.newCall(reqEnd).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                val hourly = json.optJSONObject("hourly") ?: return null

                val times = hourly.optJSONArray("time") ?: return null
                val temps = hourly.optJSONArray("temperature_2m")
                val rainProbs = hourly.optJSONArray("precipitation_probability")
                val rainMms = hourly.optJSONArray("precipitation")
                val codes = hourly.optJSONArray("weather_code")
                val isDays = hourly.optJSONArray("is_day")

                // Tìm index có unixtime gần nhất với ETA và ETA+1h
                var etaIdx = 0
                var eta1Idx = 0
                var minDiffEta = Long.MAX_VALUE
                var minDiffEta1 = Long.MAX_VALUE

                for (i in 0 until times.length()) {
                    val t = times.optLong(i, 0L)
                    val diffEta = Math.abs(t - etaSec)
                    if (diffEta < minDiffEta) {
                        minDiffEta = diffEta
                        etaIdx = i
                    }
                    val diffEta1 = Math.abs(t - etaPlus1hSec)
                    if (diffEta1 < minDiffEta1) {
                        minDiffEta1 = diffEta1
                        eta1Idx = i
                    }
                }

                val etaInterp = interpretOpenMeteoCode(
                    code = codes?.optInt(etaIdx, 0) ?: 0,
                    precip = rainMms?.optDouble(etaIdx, 0.0) ?: 0.0,
                    prob = rainProbs?.optInt(etaIdx, 0) ?: 0,
                    temp = temps?.optDouble(etaIdx, 28.0)?.toInt() ?: 28,
                    isDay = isDays?.optInt(etaIdx, 1) ?: 1
                )

                val eta1Interp = interpretOpenMeteoCode(
                    code = codes?.optInt(eta1Idx, 0) ?: 0,
                    precip = rainMms?.optDouble(eta1Idx, 0.0) ?: 0.0,
                    prob = rainProbs?.optInt(eta1Idx, 0) ?: 0,
                    temp = temps?.optDouble(eta1Idx, 27.0)?.toInt() ?: 27,
                    isDay = isDays?.optInt(eta1Idx, 1) ?: 1
                )

                return EtaFullWeatherResult(
                    travelMinutes = travelMinutes,
                    etaTimeStr = etaTimeStr,
                    startPointWeather = startWeatherPoint,
                    etaPointWeather = PointWeather(
                        timeLabel = etaTimeStr,
                        tempC = etaInterp.temp,
                        icon = etaInterp.icon,
                        status = etaInterp.status,
                        isRainAlert = etaInterp.isRainAlert
                    ),
                    etaPlus1hPointWeather = PointWeather(
                        timeLabel = etaPlus1hTimeStr,
                        tempC = eta1Interp.temp,
                        icon = eta1Interp.icon,
                        status = eta1Interp.status,
                        isRainAlert = eta1Interp.isRainAlert
                    ),
                    provider = "Open-Meteo (ECMWF/JMA)"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("WeatherEtaService", "Error checking Open-Meteo weather: ${e.message}")
            return null
        }
    }

    private data class InterpResult(val icon: String, val status: String, val temp: Int, val isRainAlert: Boolean)

    private fun interpretWeatherApiCode(code: Int, text: String, precip: Double, prob: Int, temp: Int, isDay: Int = 1): InterpResult {
        var status = if (text.isNotEmpty()) text else (if (isDay == 1) "Nắng ráo" else "Trời quang")
        var isRain = false
        var icon = if (isDay == 1) "☀️" else "🌙"

        when (code) {
            1000 -> { // Sunny / Clear
                status = if (isDay == 1) "Trời nắng" else "Trời quang"
                icon = if (isDay == 1) "☀️" else "🌙"
            }
            1003 -> { // Partly cloudy
                status = if (prob in 20..50) "Ít mây (${prob}% mưa)" else "Ít mây"
                icon = if (isDay == 1) "🌤️" else "☁️"
            }
            1006, 1009 -> { // Cloudy / Overcast
                status = if (prob in 20..50) "Nhiều mây (${prob}% mưa)" else "Nhiều mây"
                icon = "☁️"
            }
            1030, 1135, 1147 -> { // Mist / Fog
                status = "Sương mù"
                icon = "🌫️"
            }
            1087, 1273, 1276, 1279, 1282 -> { // Thunderstorm
                status = if (prob > 0) "Dông sét ($prob%)" else "Dông sét"
                isRain = true
                icon = "⚡"
            }
            1063, 1150, 1153, 1180, 1183 -> { // Light rain / Drizzle
                status = if (precip > 0.0) "Mưa nhỏ ${precip}mm ($prob%)" else "Mưa nhỏ"
                isRain = true
                icon = "🌦️"
            }
            1186, 1189, 1192, 1195, 1240, 1243, 1246 -> { // Heavy/Moderate Rain / Showers
                status = if (precip > 0.0) "Mưa rào ${precip}mm ($prob%)" else "Mưa rào"
                isRain = true
                icon = "🌧️"
            }
            else -> {
                if (precip > 0.2 || prob >= 50) {
                    status = if (precip > 0.0) "Có mưa ${precip}mm ($prob%)" else "Khả năng mưa ($prob%)"
                    isRain = true
                    icon = "🌧️"
                }
            }
        }

        return InterpResult(icon, status, temp, isRain)
    }

    private fun interpretOpenMeteoCode(code: Int, precip: Double, prob: Int, temp: Int, isDay: Int = 1): InterpResult {
        var status = if (isDay == 1) "Nắng ráo" else "Trời quang"
        var isRain = false
        var icon = if (isDay == 1) "☀️" else "🌙"

        when {
            code >= 95 -> {
                status = if (prob > 0) "Dông sét ($prob%)" else "Dông sét"
                isRain = true
                icon = "⚡"
            }
            code in 80..82 -> {
                status = if (precip > 0.0) "Mưa rào ${precip}mm ($prob%)" else if (prob > 0) "Mưa rào ($prob%)" else "Mưa rào"
                isRain = true
                icon = "🌧️"
            }
            code in 61..65 -> {
                val intensity = when (code) {
                    61 -> "Mưa nhỏ"
                    63 -> "Mưa vừa"
                    else -> "Mưa to"
                }
                status = if (precip > 0.0) "$intensity ${precip}mm ($prob%)" else if (prob > 0) "$intensity ($prob%)" else intensity
                isRain = true
                icon = "🌧️"
            }
            code in 51..55 -> {
                status = if (prob > 0) "Mưa phùn ($prob%)" else "Mưa phùn"
                isRain = true
                icon = "🌦️"
            }
            code in 45..48 -> {
                status = "Sương mù"
                icon = "🌫️"
            }
            precip > 0.2 || prob >= 60 -> {
                status = if (precip > 0.0) "Có mưa ${precip}mm ($prob%)" else "Khả năng mưa ($prob%)"
                isRain = true
                icon = "🌧️"
            }
            code == 3 -> {
                status = if (prob in 20..59) "Nhiều mây ($prob% mưa)" else "Nhiều mây"
                icon = "☁️"
            }
            code == 2 -> {
                status = if (prob in 20..59) "Có mây ($prob% mưa)" else if (isDay == 1) "Có mây" else "Mây rải rác"
                icon = if (isDay == 1) "⛅" else "☁️"
            }
            code == 1 -> {
                status = if (isDay == 1) "Nắng nhẹ" else "Ít mây"
                icon = if (isDay == 1) "🌤️" else "🌙"
            }
            code == 0 -> {
                status = if (isDay == 1) "Trời nắng" else "Trời quang"
                icon = if (isDay == 1) "☀️" else "🌙"
            }
        }

        return InterpResult(icon, status, temp, isRain)
    }
}
