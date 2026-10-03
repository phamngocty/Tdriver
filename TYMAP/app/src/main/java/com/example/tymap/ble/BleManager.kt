package com.example.tymap.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.example.tymap.repository.NavigationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.PhyRequest
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MyBleManager(context: Context) : BleManager(context) {
    private var navChar: BluetoothGattCharacteristic? = null
    private var navIconChar: BluetoothGattCharacteristic? = null
    private var speedChar: BluetoothGattCharacteristic? = null
    private var settingsChar: BluetoothGattCharacteristic? = null
    private var timeChar: BluetoothGattCharacteristic? = null
    private var weatherChar: BluetoothGattCharacteristic? = null
    private var mapImageChar: BluetoothGattCharacteristic? = null
    private var deviceCtrlChar: BluetoothGattCharacteristic? = null
    private var remoteCmdChar: BluetoothGattCharacteristic? = null
    private var deviceStatusChar: BluetoothGattCharacteristic? = null
    private var iconDataChar: BluetoothGattCharacteristic? = null
    private var oledImageChar: BluetoothGattCharacteristic? = null
    private var notificationChar: BluetoothGattCharacteristic? = null
    private var phoneBatteryChar: BluetoothGattCharacteristic? = null
    private var warningChar: BluetoothGattCharacteristic? = null
    
    private var mapTileChar: BluetoothGattCharacteristic? = null
    private var mapCtrlChar: BluetoothGattCharacteristic? = null
    private var mapStatusChar: BluetoothGattCharacteristic? = null
    private var otaChar: BluetoothGattCharacteristic? = null

    init {
        connectionObserver = object : ConnectionObserver {
            override fun onDeviceConnecting(device: BluetoothDevice) {
                NavigationRepository.updateBleConnectionState(NavigationRepository.BleConnectionState.Connecting)
            }
            override fun onDeviceConnected(device: BluetoothDevice) {
                NavigationRepository.updateBleConnectionState(NavigationRepository.BleConnectionState.Connected)
            }
            override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) {
                NavigationRepository.updateBleConnectionState(NavigationRepository.BleConnectionState.Disconnected)
            }
            override fun onDeviceReady(device: BluetoothDevice) {
                NavigationRepository.updateBleConnectionState(NavigationRepository.BleConnectionState.Ready)
            }
            override fun onDeviceDisconnecting(device: BluetoothDevice) {}
            override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
                NavigationRepository.updateBleConnectionState(NavigationRepository.BleConnectionState.Disconnected)
                lastSentSpeed = -1
                lastSentNavData = ""
            }
        }
    }

    override fun getGattCallback(): BleManagerGattCallback = MyGattCallback()

    override fun log(priority: Int, message: String) {
        Log.println(priority, "BleManager", message)
        NavigationRepository.addLog(message)
    }

    private inner class MyGattCallback : BleManagerGattCallback() {
        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(BleConstants.SERVICE_UUID) ?: return false
            navChar = service.getCharacteristic(BleConstants.CHA_NAV)
            navIconChar = service.getCharacteristic(BleConstants.CHA_NAV_TBT_ICON)
            speedChar = service.getCharacteristic(BleConstants.CHA_GPS_SPEED)
            settingsChar = service.getCharacteristic(BleConstants.CHA_SETTINGS)
            timeChar = service.getCharacteristic(BleConstants.CHA_TIME)
            weatherChar = service.getCharacteristic(BleConstants.CHA_WEATHER)
            mapImageChar = service.getCharacteristic(BleConstants.CHA_MAP_IMAGE)
            deviceCtrlChar = service.getCharacteristic(BleConstants.CHA_DEVICE_CTRL)
            remoteCmdChar = service.getCharacteristic(BleConstants.CHA_REMOTE_CMD)
            deviceStatusChar = service.getCharacteristic(BleConstants.CHA_DEVICE_STATUS)
            iconDataChar = service.getCharacteristic(BleConstants.CHA_ICON_DATA)
            oledImageChar = service.getCharacteristic(BleConstants.CHA_OLED_IMAGE)
            notificationChar = service.getCharacteristic(BleConstants.CHA_NOTIFICATION)
            phoneBatteryChar = service.getCharacteristic(BleConstants.CHA_PHONE_BATTERY)
            warningChar = service.getCharacteristic(BleConstants.CHA_WARNING)

            mapTileChar = service.getCharacteristic(BleConstants.CHA_MAP_TILE)
            mapCtrlChar = service.getCharacteristic(BleConstants.CHA_MAP_CTRL)
            mapStatusChar = service.getCharacteristic(BleConstants.CHA_MAP_STATUS)
            otaChar = service.getCharacteristic(BleConstants.CHA_OTA)

            val criticalMissing = mutableListOf<String>()
            if (navChar == null) criticalMissing.add("NAV")
            if (speedChar == null) criticalMissing.add("SPEED")

            if (criticalMissing.isNotEmpty()) {
                NavigationRepository.addLog("Missing critical characteristics: ${criticalMissing.joinToString()}")
                return false
            }

            // Log optional characteristics
            val optionalMissing = mutableListOf<String>()
            if (navIconChar == null) optionalMissing.add("NAV_ICON")
            if (settingsChar == null) optionalMissing.add("SETTINGS")
            if (timeChar == null) optionalMissing.add("TIME")
            if (weatherChar == null) optionalMissing.add("WEATHER")
            if (deviceCtrlChar == null) optionalMissing.add("DEVICE_CTRL")
            if (remoteCmdChar == null) optionalMissing.add("REMOTE_CMD")
            if (deviceStatusChar == null) optionalMissing.add("DEVICE_STATUS")
            if (iconDataChar == null) optionalMissing.add("ICON_DATA")
            if (notificationChar == null) optionalMissing.add("NOTIFICATION")
            if (oledImageChar == null) optionalMissing.add("OLED_IMAGE")

            if (optionalMissing.isNotEmpty()) {
                NavigationRepository.addLog("Optional characteristics not found: ${optionalMissing.joinToString()}")
            }

            return true
        }

        override fun initialize() {
            requestMtu(512)
                .fail { _, _ -> log(Log.WARN, "MTU 512 request failed, falling back to default") }
                .enqueue()
            requestConnectionPriority(no.nordicsemi.android.ble.ConnectionPriorityRequest.CONNECTION_PRIORITY_HIGH)
                .fail { _, _ -> log(Log.WARN, "Priority HIGH request failed") }
                .enqueue()
            
            // Listen for device control (zoom, refresh request)
            deviceCtrlChar?.let { char ->
                setNotificationCallback(char).with { _, data ->
                    val bitfield = data.getByte(0)?.toInt() ?: 0
                    handleDeviceCtrl(bitfield)
                }
                enableNotifications(char)
                    .fail { _, status -> log(Log.WARN, "Failed to enable deviceCtrl notifications: $status") }
                    .enqueue()
            }

            // Tự động đồng bộ thời gian ngay khi kết nối BLE thành công
            syncTime()

            // Listen for device status (mode, voltage, rssi, ping)
            deviceStatusChar?.let { char ->
                setNotificationCallback(char).with { _, data ->
                    val rawBytes = data.value ?: byteArrayOf()
                    val nullIndex = rawBytes.indexOf(0.toByte())
                    val cleanBytes = if (nullIndex >= 0) rawBytes.copyOfRange(0, nullIndex) else rawBytes
                    val statusText = String(cleanBytes, Charsets.UTF_8)
                    Log.d("BleManager", "Received device status notification, rawText='$statusText', bytesCount=${rawBytes.size}")
                    handleDeviceStatus(statusText)
                }
                enableNotifications(char)
                    .fail { _, status -> log(Log.WARN, "Failed to enable deviceStatus notifications: $status") }
                    .enqueue()
            }

            mapStatusChar?.let { char ->
                setNotificationCallback(char).with { _, data ->
                    val rawBytes = data.value ?: byteArrayOf()
                    val nullIndex = rawBytes.indexOf(0.toByte())
                    val cleanBytes = if (nullIndex >= 0) rawBytes.copyOfRange(0, nullIndex) else rawBytes
                    val statusText = String(cleanBytes, Charsets.UTF_8)
                    handleMapStatus(statusText)
                }
                enableNotifications(char)
                    .fail { _, status -> log(Log.WARN, "Failed to enable mapStatus notifications: $status") }
                    .enqueue()
            }
        }

        override fun onServicesInvalidated() {
            navChar = null
            navIconChar = null
            speedChar = null
            settingsChar = null
            timeChar = null
            weatherChar = null
            mapImageChar = null
            deviceCtrlChar = null
            remoteCmdChar = null
            deviceStatusChar = null
            iconDataChar = null
            oledImageChar = null
            notificationChar = null
            phoneBatteryChar = null
            mapTileChar = null
            mapCtrlChar = null
            mapStatusChar = null
            warningChar = null
            otaChar = null
        }
    }

    private fun handleDeviceCtrl(bitfield: Int) {
        val zoomIn = (bitfield and 1) != 0
        val zoomOut = (bitfield and 2) != 0
        val mapMode = (bitfield and 4) != 0
        val refreshMap = (bitfield and 8) != 0
        NavigationRepository.addLog("Device Ctrl: zoomIn=$zoomIn, zoomOut=$zoomOut, mapMode=$mapMode, refresh=$refreshMap")
        
        if (zoomIn) NavigationRepository.triggerRemoteZoom(true)
        if (zoomOut) NavigationRepository.triggerRemoteZoom(false)
        NavigationRepository.setMapModeActive(mapMode)
    }

    private fun handleDeviceStatus(status: String) {
        NavigationRepository.addLog("Device Status: $status")
        val lines = status.split("\n")
        val statusMap = mutableMapOf<String, String>()
        lines.forEach { line ->
            val parts = line.split("=")
            if (parts.size == 2) {
                val key = parts[0].trim()
                val value = parts[1].trim()
                statusMap[key] = value
                
                // Handle Icon Request
                if (key == "icon_req") {
                    NavigationRepository.requestIconData(value)
                }
            }
        }
        NavigationRepository.updateDeviceStatus(statusMap)
        
        // Tự động đồng bộ phiên bản Firmware thực tế đang chạy trên ESP32
        statusMap["ver"]?.let { fwVer ->
            if (fwVer.isNotEmpty()) {
                com.example.tymap.utils.PrefsHelper.putString(context, "esp32_fw_version_name", fwVer)
            }
        }
        statusMap["fw_code"]?.toIntOrNull()?.let { fwCode ->
            if (fwCode > 0) {
                com.example.tymap.utils.PrefsHelper.putInt(context, "esp32_fw_version_code", fwCode)
            }
        }
        statusMap["display"]?.let { disp ->
            if (disp.isNotEmpty()) {
                com.example.tymap.utils.PrefsHelper.putString(context, "connected_device_display", disp)
            }
        }
        statusMap["slot"]?.let { slot ->
            if (slot.isNotEmpty()) {
                com.example.tymap.utils.PrefsHelper.putString(context, "esp32_running_slot", slot)
            }
        }

        // Tự động kích hoạt đồng bộ thời gian nếu thiết bị báo chưa đồng bộ
        if (statusMap["timeSynced"] == "0") {
            syncTime()
        }

        // Ghi nhận trạng thái thiết bị, không ép cập nhật MapModeActive để tránh vòng lặp phản hồi (ping-pong loop)
    }

    fun syncTime() {
        writeTime(System.currentTimeMillis())
    }

    fun writeHudData(json: String) {
        val char = navChar ?: return
        NavigationRepository.addLog("BLE OUT: HUD JSON -> $json")
        writeCharacteristic(char, json.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    fun writeNavIconHash(hashHex: String) {
        val char = navIconChar ?: return
        val data = "hash=$hashHex".toByteArray()
        Log.d("BleManager", "Writing Icon Hash: $hashHex (${data.size} bytes)")
        NavigationRepository.addLog("BLE OUT: Icon Hash -> $hashHex")
        writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    fun writeIconData(hash: Long, bitmap: ByteArray) {
        val char = iconDataChar ?: return
        val buffer = ByteBuffer.allocate(4 + bitmap.size).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(hash.toInt())
        buffer.put(bitmap)
        val finalData = buffer.array()
        Log.d("BleManager", "Writing Icon Bitmap: Hash=${String.format("%08X", hash)}, Size=${finalData.size} bytes")
        NavigationRepository.addLog("BLE OUT: Icon Bitmap Data -> Hash=${String.format("%08X", hash)}, Size=${finalData.size} bytes")
        writeCharacteristic(char, finalData, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    fun writeNotification(json: String) {
        val char = notificationChar ?: return
        NavigationRepository.addLog("BLE OUT: Notif -> $json")
        writeCharacteristic(char, json.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    suspend fun writeOledImage(bitmapData: ByteArray) {
        val char = oledImageChar ?: return

        try {
            if (bitmapData.size >= 1024) {
                val previewBmp = android.graphics.Bitmap.createBitmap(128, 64, android.graphics.Bitmap.Config.ARGB_8888)
                for (y in 0 until 64) {
                    for (x in 0 until 128) {
                        val byteIdx = (y / 8) * 128 + x
                        val bitPos = y % 8
                        val isWhite = ((bitmapData[byteIdx].toInt() shr bitPos) and 1) != 0
                        previewBmp.setPixel(x, y, if (isWhite) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
                    }
                }
                NavigationRepository.updateLastSentMapImage(previewBmp)
            }
        } catch (e: Exception) {
            Log.e("BleManager", "Error decoding OLED bitmap preview: ${e.message}")
        }

        val sizeBuffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        sizeBuffer.putShort(bitmapData.size.toShort())
        
        NavigationRepository.addLog("BLE OUT: OLED Image Start -> Size=${bitmapData.size} bytes")
        writeCharacteristic(char, sizeBuffer.array(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).suspend()
        kotlinx.coroutines.delay(12)

        val mtu = mtu - 3
        var offset = 0
        while (offset < bitmapData.size) {
            val length = kotlin.math.min(mtu, bitmapData.size - offset)
            val chunk = bitmapData.copyOfRange(offset, offset + length)
            writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).suspend()
            offset += length
            if (offset < bitmapData.size) {
                kotlinx.coroutines.delay(12)
            }
        }
    }

    private var lastSentNavData: String = ""
    private var lastSentNavDataTime: Long = 0L

    fun writeNavigationData(data: String) {
        val char = navChar ?: return
        val now = System.currentTimeMillis()
        // Tối ưu băng thông: Bỏ qua nếu dữ liệu điều hướng y hệt lần trước và chưa quá 2 giây
        if (data == lastSentNavData && (now - lastSentNavDataTime < 2000L)) {
            return
        }
        lastSentNavData = data
        lastSentNavDataTime = now
        NavigationRepository.updatePreparedBleData(data)
        NavigationRepository.addLog("BLE OUT: Nav Text ->\n$data")
        writeCharacteristic(char, data.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    fun writeNavIconData(data: ByteArray) {
        val char = navIconChar ?: return
        // Send 1bpp monochrome icon data (48x48 = 288 bytes)
        NavigationRepository.addLog("BLE OUT: Raw Icon Data -> Size=${data.size} bytes")
        writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    private var lastSentSpeed: Int = -1
    private var lastSentSpeedTime: Long = 0L

    fun writeSpeed(speed: Int) {
        val char = speedChar ?: return
        val safeSpeed = speed.coerceIn(0, 250)
        val now = System.currentTimeMillis()
        // Tối ưu băng thông: Nếu tốc độ không đổi và chưa quá 3 giây thì không gửi lặp lại
        if (safeSpeed == lastSentSpeed && (now - lastSentSpeedTime < 3000L)) {
            return
        }
        lastSentSpeed = safeSpeed
        lastSentSpeedTime = now
        NavigationRepository.addLog("BLE OUT: Speed -> $safeSpeed km/h")
        // Dùng WRITE_TYPE_NO_RESPONSE để không cạnh tranh round-trip ACK với luồng truyền ảnh JPEG (CHA_MAP_IMAGE)
        writeCharacteristic(char, safeSpeed.toString().toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).enqueue()
    }

    fun writeGpsSpeedAndPosition(speed: Int, bearing: Int, px: Int, py: Int) {
        val char = speedChar ?: return
        val text = "speed=$speed,bearing=$bearing,px=$px,py=$py"
        NavigationRepository.addLog("BLE OUT: GPS Pos -> $text")
        writeCharacteristic(char, text.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).enqueue()
    }

    fun writeSettings(settings: String) {
        val char = settingsChar ?: return
        NavigationRepository.addLog("BLE OUT: Settings -> $settings")
        writeCharacteristic(char, settings.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    fun writeTime(timestamp: Long) {
        val char = timeChar ?: return
        // Cộng thêm Offset múi giờ địa phương (ví dụ +7h cho Việt Nam)
        val tz = java.util.TimeZone.getDefault()
        val localTimestamp = timestamp + tz.getOffset(timestamp)
        val epochSeconds = (localTimestamp / 1000).toInt()
        
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(epochSeconds)
        NavigationRepository.addLog("BLE OUT: Sync Time -> Epoch $epochSeconds")
        writeCharacteristic(char, buffer.array(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            .done { log(Log.INFO, "Time synchronized with device: $epochSeconds (Local GMT+${tz.rawOffset / 3600000})") }
            .fail { _, status -> log(Log.WARN, "Failed to sync time: $status") }
            .enqueue()
        Log.d("BleManager", "Sent Local Time: $epochSeconds (Offset: ${tz.rawOffset / 3600000}h)")
    }

    fun writeWeather(json: String) {
        val char = weatherChar ?: return
        NavigationRepository.addLog("BLE OUT: Weather -> $json")
        writeCharacteristic(char, json.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    fun sendRemoteCommand(command: Byte) {
        val char = remoteCmdChar ?: return
        NavigationRepository.addLog("BLE OUT: Command -> 0x${String.format("%02X", command)}")
        writeCharacteristic(char, byteArrayOf(command), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    fun writeManualZoom(zoomIn: Boolean) {
        val char = deviceCtrlChar ?: return
        val value = if (zoomIn) 1.toByte() else 2.toByte()
        NavigationRepository.addLog("BLE OUT: Remote Zoom -> ${if (zoomIn) "IN" else "OUT"}")
        writeCharacteristic(char, byteArrayOf(value), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    private var lastMapPreviewDecodeTime: Long = 0L

    suspend fun writeMapImage(jpegData: ByteArray) {
        val char = mapImageChar ?: return
        
        val now = System.currentTimeMillis()
        if (now - lastMapPreviewDecodeTime > 2000L) {
            lastMapPreviewDecodeTime = now
            try {
                val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                if (bitmap != null) {
                    NavigationRepository.updateLastSentMapImage(bitmap)
                }
            } catch (e: Throwable) {
                Log.e("BleManager", "Error decoding sent map image: ${e.message}")
            }
        }

        val payload = ByteArray(4 + jpegData.size)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(jpegData.size)
        System.arraycopy(jpegData, 0, payload, 4, jpegData.size)

        NavigationRepository.addLog("BLE OUT: Map JPEG Start -> Size=${jpegData.size} bytes (Payload: ${payload.size} bytes)")
        writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .split()
            .suspend()
    }

    fun writePhoneBattery(level: Int, charging: Boolean) {
        val char = phoneBatteryChar ?: return
        val json = "{\"level\":$level,\"charging\":$charging}"
        NavigationRepository.addLog("BLE OUT: Phone Battery -> $level%, Charging=$charging")
        writeCharacteristic(char, json.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    fun writeNotification(app: String, title: String, msg: String) {
        val char = notificationChar ?: return
        val json = "{\"app\":\"$app\",\"title\":\"$title\",\"msg\":\"$msg\"}"
        NavigationRepository.addLog("BLE OUT: Notification -> $json")
        writeCharacteristic(char, json.toByteArray(), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    suspend fun writeMapTile(header: ByteArray, jpegData: ByteArray) {
        val char = mapTileChar ?: return
        val data = ByteArray(header.size + jpegData.size)
        System.arraycopy(header, 0, data, 0, header.size)
        System.arraycopy(jpegData, 0, data, header.size, jpegData.size)
        
        Log.d("BleManager", "Writing Map Tile Chunks: HeaderSize=${header.size}, JPEGSize=${jpegData.size} bytes")
        NavigationRepository.addLog("BLE OUT: Map Tile -> Size=${data.size} bytes")
        writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .split()
            .suspend()
    }

    fun writeMapCtrl(command: Byte, params: ByteArray) {
        val char = mapCtrlChar ?: return
        val data = ByteArray(1 + params.size)
        data[0] = command
        System.arraycopy(params, 0, data, 1, params.size)
        
        Log.d("BleManager", "Writing Map Ctrl command: 0x${String.format("%02X", command)}")
        NavigationRepository.addLog("BLE OUT: Map Ctrl -> Command=0x${String.format("%02X", command)}, ParamsSize=${params.size}")
        writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    private fun handleMapStatus(statusText: String) {
        NavigationRepository.addLog("Map Status: $statusText")
        val lines = statusText.split("\n")
        val statusMap = mutableMapOf<String, String>()
        lines.forEach { line ->
            val parts = line.split("=")
            if (parts.size == 2) {
                statusMap[parts[0].trim()] = parts[1].trim()
            }
        }
        
        // Gộp chung vào deviceStatus trong Repository để cập nhật UI
        val currentStatus = NavigationRepository.deviceStatus.value.toMutableMap()
        currentStatus.putAll(statusMap)
        NavigationRepository.updateDeviceStatus(currentStatus)
    }

    suspend fun writeEsp32FirmwareOta(binData: ByteArray, target: Int = 0, onProgress: (Int) -> Unit): Boolean {
        val char = otaChar ?: return false
        val totalSize = binData.size
        val targetName = if (target == 1) "iOS (Sygic)" else "Android"
        NavigationRepository.addLog("BLE OTA: Khởi động nạp Firmware ESP32 [$targetName] ($totalSize bytes)")
        
        // 1. Send OTA Start Command: 4 bytes total size LE + 1 byte target (0=Android, 1=iOS)
        val startHeader = ByteArray(5)
        ByteBuffer.wrap(startHeader, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalSize)
        startHeader[4] = target.toByte()
        writeCharacteristic(char, startHeader, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .split()
            .suspend()
        kotlinx.coroutines.delay(600)

        // 2. Stream Binary Chunks via BLE to CHA_OTA (Chunk size 256B, pacing 35ms an toàn tuyệt đối cho SPI Flash Erase)
        val chunkSize = 256
        var offset = 0
        while (offset < totalSize) {
            val length = Math.min(chunkSize, totalSize - offset)
            val chunk = ByteArray(length)
            System.arraycopy(binData, offset, chunk, 0, length)

            writeCharacteristic(char, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                .split()
                .suspend()

            offset += length
            val progress = ((offset.toLong() * 100) / totalSize).toInt()
            withContext(Dispatchers.Main) { onProgress(progress) }
            kotlinx.coroutines.delay(35)
        }

        // 3. Send OTA Finish & Reboot Command (1 byte 0x31) to CHA_OTA
        kotlinx.coroutines.delay(500)
        writeCharacteristic(char, byteArrayOf(0x31.toByte()), BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            .split()
            .suspend()
        NavigationRepository.addLog("BLE OTA: Đã gửi lệnh kết thúc (0x31). Đợi ESP32 hoàn tất ghi và Reboot...")
        kotlinx.coroutines.delay(1000)
        return true
    }

    /**
     * Gửi lệnh BLE yêu cầu ESP32 chuyển sang phân hệ iOS (Sygic BLE).
     */
    fun requestSwitchToIos() {
        val char = remoteCmdChar ?: return
        val payload = byteArrayOf(0x41.toByte())
        writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).enqueue()
        NavigationRepository.addLog("BLE Tx: Yêu cầu ESP32 chuyển sang chế độ iOS (Sygic)")
    }

    /**
     * Gửi lệnh BLE yêu cầu ESP32 chuyển sang chế độ Web Portal cấu hình.
     */
    fun requestSwitchToFactory() {
        val char = remoteCmdChar ?: return
        val payload = byteArrayOf(0x40.toByte())
        writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).enqueue()
        NavigationRepository.addLog("BLE Tx: Yêu cầu ESP32 khởi động vào Web Portal Cấu Hình")
    }

    /**
     * Gửi tín hiệu Cảnh báo Giao thông (Speed Limit / Camera Phạt Nguội) tới đồng hồ ESP32 qua BLE.
     * Quy định mảng byte Payload (2 Bytes gọn nhẹ):
     * - Byte 0: Loại cảnh báo (0x01: Camera phạt nguội, 0x02: Biển giới hạn tốc độ).
     * - Byte 1: Giá trị tốc độ giới hạn (ví dụ: 50, 60, 80 km/h; hoặc 0 cho Camera).
     */
    fun sendTrafficWarning(type: Byte, speedLimit: Byte) {
        val char = warningChar ?: return
        
        // Tạo mảng byte Payload 2-byte
        val payload = byteArrayOf(type, speedLimit)
        
        Log.d("BleManager", "--> Gửi BLE Cảnh báo Giao thông: Type=${type.toInt()}, Value=${speedLimit.toInt()} km/h")
        NavigationRepository.addLog("BLE Tx Warning: Type=${if(type.toInt()==1) "Camera" else "Speed Limit ${speedLimit.toInt()}km/h"}")

        writeCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).enqueue()
    }
}
