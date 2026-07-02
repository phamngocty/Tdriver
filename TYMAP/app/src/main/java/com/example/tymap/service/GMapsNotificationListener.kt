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

    private fun parseGmapsNotification(title: String, text: String, subText: String): ParsedNavData {
        val rawTitle = title.trim()
        val rawText = text.trim()
        val rawSubText = subText.trim()
        
        var distance = ""
        var instruction = ""
        var roadName = ""
        var eta = ""
        var ete = ""

        // 1. Regex để nhận diện khoảng cách ở đầu chuỗi (ví dụ: "700 m · Chếch...", "1.2km Rẽ...", "150 m đi...")
        val leadingDistRegex = Regex("""^(\d+(?:[.,]\d+)?\s*(?:m|km))\b""", RegexOption.IGNORE_CASE)
        
        // Kiểm tra title trước
        val titleMatch = leadingDistRegex.find(rawTitle)
        if (titleMatch != null) {
            distance = titleMatch.groups[1]?.value ?: ""
            var remaining = rawTitle.substring(titleMatch.range.last + 1).trim()
            // Loại bỏ dấu ngăn cách nếu có
            if (remaining.startsWith("·") || remaining.startsWith("-") || remaining.startsWith(",")) {
                remaining = remaining.substring(1).trim()
            }
            instruction = remaining
        } else {
            // Kiểm tra text xem có khoảng cách ở đầu không (ví dụ: "150 m · Lê Lợi")
            val textMatch = leadingDistRegex.find(rawText)
            if (textMatch != null) {
                distance = textMatch.groups[1]?.value ?: ""
                var remaining = rawText.substring(textMatch.range.last + 1).trim()
                if (remaining.startsWith("·") || remaining.startsWith("-") || remaining.startsWith(",")) {
                    remaining = remaining.substring(1).trim()
                }
                roadName = remaining
                instruction = rawTitle
            } else {
                instruction = rawTitle
            }
        }

        // 2. Phân tích chi tiết các phần phân tách bằng dấu "·" trong cả text và subText
        val parts = mutableListOf<String>()
        if (rawText.isNotEmpty()) {
            parts.addAll(rawText.split("·").map { it.trim() })
        }
        if (rawSubText.isNotEmpty()) {
            parts.addAll(rawSubText.split("·").map { it.trim() })
        }

        for (part in parts) {
            if (part.isEmpty()) continue
            
            when {
                // Nhận diện ETA (Ví dụ: "23:21" hoặc "Đến nơi lúc 23:21")
                part.matches(Regex(""".*\b\d{1,2}:\d{2}\b.*""")) || part.contains("đến nơi", ignoreCase = true) || part.contains("arrive", ignoreCase = true) -> {
                    val timeMatch = Regex("""\b\d{1,2}:\d{2}\b""").find(part)
                    eta = timeMatch?.value ?: part
                }
                // Nhận diện ETE / Thời gian di chuyển (Ví dụ: "15 ph", "15 min", "1 giờ 10 ph")
                part.contains("ph", ignoreCase = true) || part.contains("min", ignoreCase = true) || part.contains("giờ", ignoreCase = true) || part.contains("hour", ignoreCase = true) || part.contains(" h", ignoreCase = true) -> {
                    ete = part
                }
                // Nhận diện khoảng cách phụ nếu chưa tìm thấy ở đầu chuỗi (ví dụ: "8.2 km")
                part.matches(Regex("""\d+(?:[.,]\d+)?\s*(?:m|km)""", RegexOption.IGNORE_CASE)) -> {
                    if (distance.isEmpty()) {
                        distance = part
                    }
                }
                // Nếu là thông tin text bình thường khác (ví dụ: tên đường)
                else -> {
                    if (roadName.isEmpty() && part != rawTitle && !part.contains("maps", ignoreCase = true)) {
                        roadName = part
                    }
                }
            }
        }

        // 3. Dự phòng trích xuất tên đường (roadName) từ câu chỉ dẫn (instruction) nếu vẫn trống
        if (roadName.isEmpty()) {
            if (instruction.contains("vào", ignoreCase = true)) {
                roadName = instruction.substringAfter("vào").trim()
            } else if (instruction.contains("onto", ignoreCase = true)) {
                roadName = instruction.substringAfter("onto").trim()
            }
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

        // Nếu thông báo trống rỗng hoàn toàn, bỏ qua
        if (title.isEmpty() && text.isEmpty()) return

        // Ưu tiên lấy icon
        val icon = sbn.notification.getLargeIcon() ?: sbn.notification.smallIcon
        val iconBitmap = icon?.loadDrawable(this)?.let { IconUtils.drawableToBitmap(it) }
        val icon1bppBytes = iconBitmap?.let { IconUtils.convertTo1bpp(it, 48, 48) }

        // Bóc tách dữ liệu sử dụng bộ phân tích robust mới
        val parsedData = parseGmapsNotification(title, text, subText)

        // CHẨN ĐOÁN: Chỉ coi là DỪNG nếu thông báo Maps cực kỳ ngắn và không có icon dẫn đường
        val hasTurnIcon = sbn.notification.getLargeIcon() != null
        val isNav = hasTurnIcon || parsedData.distance.isNotEmpty() || parsedData.instruction.contains("hướng", ignoreCase = true)

        if (!isNav) {
            Log.d("GMapsListener", "Maps notification seems static. Not starting HUD.")
            return
        }

        val logMsg = "Maps Process: dist='${parsedData.distance}', title='${parsedData.instruction}', icon=${iconBitmap != null}"
        Log.d("GMapsListener", logMsg)
        NavigationRepository.addLog(logMsg)

        val iconIndex = guessIconIndex(parsedData.instruction, text)

        val intent = Intent("com.example.tymap.ACTION_GMAPS_HUD").apply {
            putExtra("active", true)
            putExtra("distance", parsedData.distance)
            putExtra("instruction", parsedData.instruction)
            putExtra("roadName", parsedData.roadName)
            putExtra("eta", parsedData.eta)
            putExtra("ete", parsedData.ete)
            putExtra("iconIndex", iconIndex)
            putExtra("icon1bpp", icon1bppBytes)
            putExtra("bitmap", iconBitmap)
            setPackage(this@GMapsNotificationListener.packageName)
        }
        sendBroadcast(intent)
    }

    private fun forwardNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        
        if (sbn.packageName == "com.google.android.apps.maps" || (title.isEmpty() && text.isEmpty())) return

        val appLabel = try {
            val pm = packageManager
            val ai = pm.getApplicationInfo(sbn.packageName, 0)
            pm.getApplicationLabel(ai).toString()
        } catch (e: Exception) {
            sbn.packageName
        }

        val json = org.json.JSONObject().apply {
            put("app", appLabel)
            put("title", title)
            put("message", text)
        }
        
        NavigationService.bleManager?.writeNotification(json.toString())
    }

    private fun guessIconIndex(title: String, text: String): Int {
        val combined = (title + " " + text).lowercase()
        return when {
            combined.contains("quay đầu") || combined.contains("u-turn") || combined.contains("uturn") -> if (combined.contains("phải")) 8 else 7
            combined.contains("vòng xuyến") || combined.contains("roundabout") -> 9
            combined.contains("gắt") || combined.contains("sharp") -> if (combined.contains("trái") || combined.contains("left")) 3 else 6
            combined.contains("chếch") || combined.contains("slight") -> if (combined.contains("trái") || combined.contains("left")) 1 else 4
            combined.contains("trái") || combined.contains("left") -> 2
            combined.contains("phải") || combined.contains("right") -> 5
            combined.contains("đến") || combined.contains("arrive") || combined.contains("đã tới") || combined.contains("đích") -> 10
            combined.contains("thẳng") || combined.contains("straight") || combined.contains("continue") -> 0
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
