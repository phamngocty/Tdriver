package com.example.tymap.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.tymap.utils.IconUtils
import com.example.tymap.utils.PrefsHelper
import com.example.tymap.repository.NavigationRepository

class GMapsNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        processNotification(sbn)
    }

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        // Một số máy Android yêu cầu cập nhật ranking để duy trì service sống
        super.onNotificationRankingUpdate(rankingMap)
    }

    private fun processNotification(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        
        // 1. Google Maps Navigation Logic
        if (packageName == "com.google.android.apps.maps") {
            handleGoogleMapsNotification(sbn)
        }
        
        // 2. Forward allowed apps (Zalo, Messenger...)
        val allowedApps = PrefsHelper.getStringSet(this, "enabled_notifications", emptySet())
        if (allowedApps.contains(packageName)) {
            forwardNotification(sbn)
        }
    }

    private data class ParsedNavData(
        val distance: String,
        val instruction: String,
        val roadName: String,
        val eta: String,
        val ete: String
    )

    private fun parseGmapsNotification(title: String, text: String, subText: String, bigText: String = ""): ParsedNavData {
        val rawTitle = title.trim()
        val rawText = text.trim()
        val rawSubText = subText.trim()
        val rawBigText = bigText.trim()
        
        var distance = ""
        var instruction = ""
        var roadName = ""
        var eta = ""
        var ete = ""

        val distRegex = Regex("""\b(\d+(?:[.,]\d+)?\s*(?:m|km))\b""", RegexOption.IGNORE_CASE)
        val leadingDistRegex = Regex("""^(\d+(?:[.,]\d+)?\s*(?:m|km))""", RegexOption.IGNORE_CASE)

        // 1. Trích xuất khoảng cách
        val titleMatch = leadingDistRegex.find(rawTitle)
        if (titleMatch != null) {
            distance = titleMatch.groups[1]?.value ?: ""
            var remaining = rawTitle.substring(titleMatch.range.last + 1).trim()
            if (remaining.startsWith("·") || remaining.startsWith("-") || remaining.startsWith(",")) {
                remaining = remaining.substring(1).trim()
            }
            if (remaining.isNotEmpty()) {
                instruction = remaining
            }
        }

        if (distance.isEmpty()) {
            val textMatch = leadingDistRegex.find(rawText)
            if (textMatch != null) {
                distance = textMatch.groups[1]?.value ?: ""
                var remaining = rawText.substring(textMatch.range.last + 1).trim()
                if (remaining.startsWith("·") || remaining.startsWith("-") || remaining.startsWith(",")) {
                    remaining = remaining.substring(1).trim()
                }
                if (remaining.isNotEmpty()) {
                    roadName = remaining
                }
            }
        }

        if (distance.isEmpty()) {
            val inTitle = distRegex.find(rawTitle)
            if (inTitle != null) {
                distance = inTitle.groups[1]?.value ?: ""
            } else {
                val inText = distRegex.find(rawText)
                if (inText != null) {
                    distance = inText.groups[1]?.value ?: ""
                }
            }
        }

        // 2. Trích xuất câu chỉ dẫn (instruction)
        if (instruction.isEmpty()) {
            if (rawBigText.isNotEmpty() && !rawBigText.matches(distRegex)) {
                instruction = rawBigText
            } else if (rawTitle.isNotEmpty() && !rawTitle.matches(Regex("""^\d+(?:[.,]\d+)?\s*(?:m|km)$""", RegexOption.IGNORE_CASE))) {
                instruction = rawTitle
            } else if (rawText.isNotEmpty()) {
                instruction = rawText.substringBefore("·").trim()
            }
        }

        // 3. Trích xuất tên đường (roadName)
        if (roadName.isEmpty()) {
            if (rawText.isNotEmpty()) {
                val candidate = rawText.substringBefore("·").trim()
                if (candidate != instruction && !candidate.matches(Regex("""^\d+(?:[.,]\d+)?\s*(?:m|km)$""", RegexOption.IGNORE_CASE))) {
                    roadName = candidate
                } else if (rawText.contains("·")) {
                    val afterDot = rawText.substringAfter("·").trim()
                    if (!afterDot.contains("đến", ignoreCase = true) && !afterDot.matches(Regex(""".*\b\d{1,2}:\d{2}\b.*"""))) {
                        roadName = afterDot.substringBefore("·").trim()
                    }
                }
            }
        }

        // 4. Phân tích chi tiết ETA, ETE từ text, subText, bigText
        val allParts = mutableListOf<String>()
        if (rawText.isNotEmpty()) allParts.addAll(rawText.split("·").map { it.trim() })
        if (rawSubText.isNotEmpty()) allParts.addAll(rawSubText.split("·").map { it.trim() })
        if (rawBigText.isNotEmpty()) allParts.addAll(rawBigText.split("·").map { it.trim() })

        for (part in allParts) {
            if (part.isEmpty()) continue
            
            when {
                part.matches(Regex(""".*\b\d{1,2}:\d{2}\b.*""")) || part.contains("đến nơi", ignoreCase = true) || part.contains("arrive", ignoreCase = true) -> {
                    val timeMatch = Regex("""\b\d{1,2}:\d{2}\b""").find(part)
                    if (timeMatch != null && eta.isEmpty()) {
                        eta = timeMatch.value
                    }
                }
                part.contains("ph", ignoreCase = true) || part.contains("min", ignoreCase = true) || part.contains("giờ", ignoreCase = true) || part.contains("hour", ignoreCase = true) || part.contains(" h", ignoreCase = true) -> {
                    if (ete.isEmpty()) {
                        ete = part
                    }
                }
                distance.isEmpty() && distRegex.matches(part) -> {
                    distance = part
                }
                else -> {
                    if (roadName.isEmpty() && part != rawTitle && !part.contains("maps", ignoreCase = true)) {
                        roadName = part
                    }
                }
            }
        }

        // 5. Dự phòng trích xuất tên đường từ câu chỉ dẫn nếu vẫn trống
        if (roadName.isEmpty()) {
            if (instruction.contains("vào", ignoreCase = true)) {
                roadName = instruction.substringAfter("vào").trim()
            } else if (instruction.contains("onto", ignoreCase = true)) {
                roadName = instruction.substringAfter("onto").trim()
            }
        }

        // 6. Cross-fallback: Đảm bảo cả instruction và roadName không bị rỗng
        if (instruction.isEmpty() && roadName.isNotEmpty()) {
            instruction = roadName
        }
        if (roadName.isEmpty() && instruction.isNotEmpty()) {
            roadName = instruction
        }

        return ParsedNavData(
            distance = distance,
            instruction = instruction,
            roadName = roadName,
            eta = eta,
            ete = ete
        )
    }

    private fun handleGoogleMapsNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val subText = extras.getCharSequence("android.subText")?.toString() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString() ?: ""

        // Nếu thông báo trống rỗng hoàn toàn, bỏ qua
        if (title.isEmpty() && text.isEmpty() && bigText.isEmpty()) return

        // Ưu tiên lấy icon mũi tên chỉ đường thực tế (chỉ dùng getLargeIcon, không lấy smallIcon vì smallIcon là logo app Google Maps)
        val icon = sbn.notification.getLargeIcon()
        val rawIconBitmap = try {
            icon?.loadDrawable(this)?.let { IconUtils.drawableToBitmap(it, 48, 48) }
        } catch (e: Exception) {
            Log.w("GMapsListener", "Could not load notification icon: ${e.message}")
            null
        }
        val icon1bppBytes = rawIconBitmap?.let { 
            val b = IconUtils.convertTo1bpp(it, 48, 48)
            if (!IconUtils.is1bppEmpty(b)) b else null
        }
        
        // Thu nhỏ bitmap an toàn tối đa 96x96 để tránh lỗi TransactionTooLargeException gây crash app
        val safeIconBitmap = rawIconBitmap?.let {
            if (it.width > 96 || it.height > 96) {
                android.graphics.Bitmap.createScaledBitmap(it, 96, 96, true)
            } else {
                it
            }
        }

        val logIncoming = "Maps raw: title='$title', text='$text', subText='$subText', bigText='$bigText'"
        Log.d("GMapsListener", logIncoming)

        // Bóc tách dữ liệu sử dụng bộ phân tích robust
        val parsedData = parseGmapsNotification(title, text, subText, bigText)

        // Chấp nhận thông báo nếu có khoảng cách, câu hướng dẫn, hoặc là thông báo đang chạy (isOngoing)
        val isNav = parsedData.distance.isNotEmpty() || parsedData.instruction.isNotEmpty() || sbn.isOngoing
        if (!isNav) {
            Log.d("GMapsListener", "Maps notification empty. Ignoring.")
            return
        }

        val logMsg = "Maps Process: dist='${parsedData.distance}', title='${parsedData.instruction}', road='${parsedData.roadName}', icon=${safeIconBitmap != null}"
        Log.d("GMapsListener", logMsg)
        NavigationRepository.addLog(logMsg)

        val iconIndex = guessIconIndex("${parsedData.instruction} $title", text)

        val intent = Intent("com.example.tymap.ACTION_GMAPS_HUD").apply {
            putExtra("active", true)
            putExtra("distance", parsedData.distance)
            putExtra("instruction", parsedData.instruction)
            putExtra("roadName", parsedData.roadName)
            putExtra("eta", parsedData.eta)
            putExtra("ete", parsedData.ete)
            putExtra("iconIndex", iconIndex)
            putExtra("icon1bpp", icon1bppBytes)
            putExtra("bitmap", safeIconBitmap)
            setPackage(this@GMapsNotificationListener.packageName)
        }
        sendBroadcast(intent)
    }

    private fun forwardNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        var text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (text.isEmpty()) {
            text = extras.getCharSequence("android.bigText")?.toString() ?: ""
        }
        if (text.isEmpty()) {
            text = extras.getCharSequence("android.subText")?.toString() ?: ""
        }
        
        if (sbn.packageName == "com.google.android.apps.maps" || (title.isEmpty() && text.isEmpty())) return

        val appLabel = try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            if (sbn.packageName.contains("zalo", ignoreCase = true)) "Zalo" else sbn.packageName
        }

        val json = org.json.JSONObject().apply {
            put("app", appLabel)
            put("title", title)
            put("message", text)
        }
        
        Log.d("GMapsListener", "Forwarding notification over BLE: $json")
        NavigationService.bleManager?.writeNotification(json.toString())
    }

    private fun guessIconIndex(title: String, text: String): Int {
        val combined = (title + " " + text).lowercase()
        return when {
            combined.contains("đến") || combined.contains("arrive") || combined.contains("đã tới") || combined.contains("đích") -> 14
            combined.contains("quay đầu") || combined.contains("u-turn") || combined.contains("uturn") -> if (combined.contains("phải")) 8 else 7
            combined.contains("vòng xuyến") || combined.contains("bùng binh") || combined.contains("roundabout") || combined.contains("rotary") -> 12
            combined.contains("gắt") || combined.contains("sharp") || combined.contains("ngoặt") -> if (combined.contains("trái") || combined.contains("left")) 6 else 3
            combined.contains("chếch") || combined.contains("slight") || combined.contains("nhẹ") -> if (combined.contains("trái") || combined.contains("left")) 4 else 1
            combined.contains("trái") || combined.contains("left") -> 5
            combined.contains("phải") || combined.contains("right") -> 2
            combined.contains("sát") || combined.contains("keep") -> if (combined.contains("trái") || combined.contains("left")) 4 else 1
            combined.contains("nhập làn") || combined.contains("merge") -> 15
            combined.contains("thẳng") || combined.contains("straight") || combined.contains("continue") || combined.contains("tiếp") -> 0
            else -> 0
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        if (packageName == "com.google.android.apps.maps") {
            val logMsg = "Maps Notify: REMOVED. Stopping HUD."
            Log.d("GMapsListener", logMsg)
            NavigationRepository.addLog(logMsg)

            val intent = Intent("com.example.tymap.ACTION_GMAPS_HUD").apply {
                putExtra("active", false)
                setPackage(this@GMapsNotificationListener.packageName)
            }
            sendBroadcast(intent)
        }
    }
}
