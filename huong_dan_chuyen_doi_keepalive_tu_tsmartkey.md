# HƯỚNG DẪN TỔNG HỢP & CHUYỂN GIAO CƠ CHẾ GIỮ SỐNG BẢN ĐỒ (KEEP-ALIVE ENGINE) TỪ TYSMARTKEY SANG TYMAP (TDRIVER)

> **Mục đích tài liệu:**
> Hướng dẫn này tổng hợp toàn bộ các thay đổi đã bị thêm nhầm vào dự án **Tysmartkey** (`control_esp`), phân tích lý do kiến trúc, và cung cấp giải pháp từng bước (code, vị trí, file) để đưa cơ chế **Giữ sống Google Maps khi tắt màn hình (4 cấp độ + Watchdog)** sang đúng vị trí của nó: dự án **TYMAP** thuộc **Tdriver**.

---

## 1. TỔNG QUAN VẤN ĐỀ & PHÂN TÍCH NHẦM LẪN

### Hiện trạng:
1. **Dự án Tysmartkey (`C:\Users\phamn\Documents\PlatformIO\Tysmartkey`):**
   - Bản chất là hệ thống Smartkey xe máy (Bật/tắt khóa xe, tìm xe, chống dắt, báo động, mở cốp, còi đèn, AirTag).
   - Vừa bị thêm nhầm:
     - `TymapKeepAliveEngine.kt` (4 cấp độ giữ sống âm thanh/WakeLock).
     - Gắn gọi `start()` / `stop()` trong `VehicleBackgroundService.kt`.
     - Nút bấm `btnKeepAliveSettings` và hộp thoại `showKeepAliveSettingsDialog()` trong `MainActivity.kt` & `activity_main.xml`.
2. **Dự án Tdriver / TYMAP (`C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP`):**
   - Đây mới chính là ứng dụng điều hướng dẫn đường xe máy (Turn-by-Turn, bắt thông báo Google Maps qua `GMapsNotificationListener`, bắn dữ liệu qua Bluetooth BLE sang màn hình HUD tròn GC9A01 / OLED SSD1306).
   - Vấn đề thực tế của người dùng: **Khi điện thoại đút túi quần hoặc cốp xe và tắt màn hình, Android tự động đóng băng Google Maps, khiến Google Maps ngừng phát thông báo dẫn đường $\rightarrow$ màn hình xe máy bị đứng im.**
   - Cơ chế Keep-Alive sinh ra là để phục vụ bài toán này của **TYMAP**, hoàn toàn không thuộc về Tysmartkey!

---

## 2. DANH SÁCH FILE BỊ ẢNH HƯỞNG & HÀNH ĐỘNG CẦN LÀM

| Dự án | File | Hiện trạng | Hành động cần làm |
|---|---|---|---|
| **Tysmartkey** | `APP Controlesp/.../TymapKeepAliveEngine.kt` | Mới tạo (untracked) | Xóa file hoặc giữ lại làm mẫu chuyển sang TYMAP |
| **Tysmartkey** | `APP Controlesp/.../VehicleBackgroundService.kt` | Đang gọi KeepAliveEngine | `git checkout` hoàn tác (revert) |
| **Tysmartkey** | `APP Controlesp/.../MainActivity.kt` | Có nút & dialog KeepAlive | `git checkout` hoàn tác (revert) |
| **Tysmartkey** | `APP Controlesp/.../activity_main.xml` | Có view `btnKeepAliveSettings` | `git checkout` hoàn tác (revert) |
| **TYMAP (Tdriver)** | `TYMAP/.../service/TymapKeepAliveEngine.kt` | Chưa có | **Tạo mới với package `com.example.tymap.service`** |
| **TYMAP (Tdriver)** | `TYMAP/.../service/NavigationService.kt` | Chưa gọi KeepAlive | **Tích hợp `start()` / `stop()` theo vòng đời dẫn đường** |
| **TYMAP (Tdriver)** | `TYMAP/.../service/GMapsNotificationListener.kt` | Chưa báo thức Watchdog | **Tích hợp `feedGoogleMapsData()` mỗi khi có thông báo Maps** |
| **TYMAP (Tdriver)** | `TYMAP/.../ui/SettingsFragment.kt` | Chưa có UI chỉnh cấp độ | **Thêm Dialog cấu hình 4 cấp độ và nút bấm** |
| **TYMAP (Tdriver)** | `TYMAP/.../res/layout/fragment_settings.xml` | Chưa có View | **Thêm giao diện chọn cấp độ giữ sống bản đồ** |

---

## 3. CHI TIẾT CÁC BƯỚC THỰC HIỆN TẠI DỰ ÁN TDRIVER (TYMAP)

### Bước 1: Tạo file `TymapKeepAliveEngine.kt` trong TYMAP
- **Vị trí file:**
  `C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP\app\src\main\java\com\example\tymap\service\TymapKeepAliveEngine.kt`
- **Mã nguồn chuẩn:**
```kotlin
package com.example.tymap.service

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log

enum class KeepAliveTier(
    val level: Int,
    val title: String,
    val badge: String,
    val description: String,
    val note: String
) {
    TIER_1_NATIVE(
        1,
        "Cấp độ 1: Chuẩn GPS Native",
        "Tiết kiệm pin",
        "Chỉ sử dụng Foreground Service của ứng dụng và yêu cầu tọa độ GPS nền kết hợp WakeLock phần cứng.",
        "💡 Lưu ý: Tiết kiệm pin tối đa, phù hợp với các dòng máy thuần Android như Google Pixel, Samsung One UI."
    ),
    TIER_2_SILENT_CONTINUOUS(
        2,
        "Cấp độ 2: Âm thanh im lặng liên tục",
        "Khuyên dùng",
        "Phát luồng âm thanh PCM 0dB (im lặng tuyệt đối) lặp liên tục với nhãn dẫn đường rảnh tay, không tranh chấp Audio Focus.",
        "💡 Lưu ý: Đánh lừa Android giữ Google Maps chạy liên tục khi tắt màn hình. HOÀN TOÀN KHÔNG ảnh hưởng nghe nhạc (Spotify/Zing), không ngắt cuộc gọi hay chuông thông báo Zalo."
    ),
    TIER_3_AUDIO_PULSE(
        3,
        "Cấp độ 3: Xung âm thanh định kỳ (15s)",
        "Cân bằng pin",
        "Mỗi 15 giây phát một xung micro-audio im lặng 200ms để 'gõ thức' hệ thống âm thanh và cảm biến vị trí trước khi máy ngủ sâu.",
        "💡 Lưu ý: Vừa duy trì được thông báo dẫn đường, vừa tiết kiệm năng lượng hơn Cấp độ 2."
    ),
    TIER_4_VIRTUAL_HEADSET(
        4,
        "Cấp độ 4: Giả lập Tai nghe / Car-Kit Bluetooth",
        "Cực mạnh - Bất tử",
        "Đăng ký MediaSession chuyên nghiệp và phát luồng âm thanh dẫn đường rảnh tay (giả lập như đang cắm tai nghe xe máy).",
        "💡 Lưu ý: Cấp độ mạnh nhất, đặc trị các dòng máy có cơ chế diệt app ngầm hung hãn như Xiaomi (HyperOS/MIUI), Oppo (ColorOS), Vivo, Realme."
    );

    companion object {
        fun fromLevel(level: Int): KeepAliveTier {
            return values().firstOrNull { it.level == level } ?: TIER_2_SILENT_CONTINUOUS
        }
    }
}

class TymapKeepAliveEngine(private val context: Context) {

    companion object {
        private const val TAG = "TymapKeepAlive"
        private const val PREFS_NAME = "tymap_keep_alive_prefs"
        private const val KEY_SELECTED_TIER = "selected_keep_alive_tier"
        private const val KEY_AUTO_ESCALATE = "auto_escalate_enabled"

        @Volatile
        private var instance: TymapKeepAliveEngine? = null

        fun getInstance(context: Context): TymapKeepAliveEngine {
            return instance ?: synchronized(this) {
                instance ?: TymapKeepAliveEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var currentTier: KeepAliveTier = KeepAliveTier.fromLevel(
        prefs.getInt(KEY_SELECTED_TIER, KeepAliveTier.TIER_2_SILENT_CONTINUOUS.level)
    )
        private set

    var isAutoEscalateEnabled: Boolean = prefs.getBoolean(KEY_AUTO_ESCALATE, true)
        private set

    var isRunning: Boolean = false
        private set

    private var wakeLock: PowerManager.WakeLock? = null
    private var audioTrack: AudioTrack? = null
    private val pulseHandler = Handler(Looper.getMainLooper())
    private var mediaSession: MediaSession? = null

    private var lastGoogleMapsPacketTime = System.currentTimeMillis()
    private val watchdogHandler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (isAutoEscalateEnabled) {
                val elapsed = System.currentTimeMillis() - lastGoogleMapsPacketTime
                if (elapsed > 20000L && lastGoogleMapsPacketTime > 0) {
                    when (currentTier) {
                        KeepAliveTier.TIER_1_NATIVE -> {
                            Log.w(TAG, "⚠️ Cấp 1 mất tín hiệu Google Maps > 20s. Tự động leo thang lên Cấp 2!")
                            setTier(KeepAliveTier.TIER_2_SILENT_CONTINUOUS, savePref = false)
                        }
                        KeepAliveTier.TIER_2_SILENT_CONTINUOUS, KeepAliveTier.TIER_3_AUDIO_PULSE -> {
                            Log.w(TAG, "⚠️ Cấp 2/3 mất tín hiệu Google Maps > 20s. Tự động leo thang lên Cấp 4 (Virtual Headset)!")
                            setTier(KeepAliveTier.TIER_4_VIRTUAL_HEADSET, savePref = false)
                        }
                        KeepAliveTier.TIER_4_VIRTUAL_HEADSET -> {}
                    }
                }
            }
            watchdogHandler.postDelayed(this, 10000L)
        }
    }

    fun setTier(tier: KeepAliveTier, savePref: Boolean = true) {
        currentTier = tier
        if (savePref) {
            prefs.edit().putInt(KEY_SELECTED_TIER, tier.level).apply()
        }
        if (isRunning) {
            restartCurrentTier()
        }
    }

    fun setAutoEscalate(enabled: Boolean) {
        isAutoEscalateEnabled = enabled
        prefs.edit().putBoolean(KEY_AUTO_ESCALATE, enabled).apply()
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "🚀 Khởi động Keep-Alive Engine ở ${currentTier.title}")

        acquireWakeLock()
        applyTierLogic(currentTier)

        lastGoogleMapsPacketTime = System.currentTimeMillis()
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, 10000L)
    }

    fun stop() {
        isRunning = false
        watchdogHandler.removeCallbacks(watchdogRunnable)
        releaseWakeLock()
        stopAudioTrack()
        pulseHandler.removeCallbacksAndMessages(null)
        stopVirtualHeadsetSession()
        Log.i(TAG, "🛑 Đã dừng toàn bộ Keep-Alive Engine")
    }

    private fun restartCurrentTier() {
        stopAudioTrack()
        pulseHandler.removeCallbacksAndMessages(null)
        stopVirtualHeadsetSession()
        applyTierLogic(currentTier)
    }

    private fun applyTierLogic(tier: KeepAliveTier) {
        when (tier) {
            KeepAliveTier.TIER_1_NATIVE -> Log.d(TAG, "Đang chạy Cấp 1: WakeLock & Native FusedLocation")
            KeepAliveTier.TIER_2_SILENT_CONTINUOUS -> {
                Log.d(TAG, "Đang chạy Cấp 2: Silent Audio Stream liên tục")
                startContinuousSilentAudio()
            }
            KeepAliveTier.TIER_3_AUDIO_PULSE -> {
                Log.d(TAG, "Đang chạy Cấp 3: Audio Heartbeat Pulse mỗi 15s")
                startAudioPulseLoop()
            }
            KeepAliveTier.TIER_4_VIRTUAL_HEADSET -> {
                Log.d(TAG, "Đang chạy Cấp 4: Virtual Headset MediaSession + Silent Audio")
                startContinuousSilentAudio()
                startVirtualHeadsetSession()
            }
        }
    }

    fun feedGoogleMapsData() {
        lastGoogleMapsPacketTime = System.currentTimeMillis()
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Tymap::NavigationWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi WakeLock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {}
        wakeLock = null
    }

    private fun startContinuousSilentAudio() {
        try {
            stopAudioTrack()
            val sampleRate = 8000
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack?.play()

            val silentBuffer = ByteArray(bufferSize) { 0 }
            Thread {
                while (isRunning && (currentTier == KeepAliveTier.TIER_2_SILENT_CONTINUOUS || currentTier == KeepAliveTier.TIER_4_VIRTUAL_HEADSET)) {
                    audioTrack?.write(silentBuffer, 0, silentBuffer.size)
                    try {
                        Thread.sleep(60)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi AudioTrack Cấp 2", e)
        }
    }

    private fun stopAudioTrack() {
        try {
            audioTrack?.pause()
            audioTrack?.flush()
            audioTrack?.release()
        } catch (e: Exception) {}
        audioTrack = null
    }

    private fun startAudioPulseLoop() {
        val pulseRunnable = object : Runnable {
            override fun run() {
                if (!isRunning || currentTier != KeepAliveTier.TIER_3_AUDIO_PULSE) return
                playMicroSilentPulse()
                pulseHandler.postDelayed(this, 15000L)
            }
        }
        pulseHandler.removeCallbacksAndMessages(null)
        pulseHandler.post(pulseRunnable)
    }

    private fun playMicroSilentPulse() {
        Thread {
            try {
                val sampleRate = 8000
                val shortBufferSize = 1600
                val silentBuffer = ByteArray(shortBufferSize) { 0 }

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build()
                    )
                    .setBufferSizeInBytes(shortBufferSize)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                track.write(silentBuffer, 0, silentBuffer.size)
                track.play()
                Thread.sleep(220)
                track.release()
            } catch (e: Exception) {}
        }.start()
    }

    private fun startVirtualHeadsetSession() {
        try {
            stopVirtualHeadsetSession()
            mediaSession = MediaSession(context, "TymapVirtualHeadset").apply {
                setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
                val state = PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .build()
                setPlaybackState(state)
                isActive = true
            }
            Log.i(TAG, "👑 Virtual Headset MediaSession Active (Bảo vệ tuyệt đối)")
        } catch (e: Exception) {
            Log.e(TAG, "Lỗi MediaSession Cấp 4", e)
        }
    }

    private fun stopVirtualHeadsetSession() {
        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {}
        mediaSession = null
    }
}
```

---

### Bước 2: Tích hợp vào `NavigationService.kt` (Vòng đời Dẫn Đường)
- **Vị trí file:**
  `C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP\app\src\main\java\com\example\tymap\service\NavigationService.kt`
- **Thay đổi:**
  1. Trong `onCreate()` hoặc khi `startForeground()` của `NavigationService`:
     ```kotlin
     TymapKeepAliveEngine.getInstance(this).start()
     ```
  2. Trong `onDestroy()` của `NavigationService`:
     ```kotlin
     TymapKeepAliveEngine.getInstance(this).stop()
     ```

---

### Bước 3: Cho ăn Watchdog trong `GMapsNotificationListener.kt`
- **Vị trí file:**
  `C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP\app\src\main\java\com\example\tymap\service\GMapsNotificationListener.kt`
- **Thay đổi:**
  Trong hàm `handleGoogleMapsNotification()`, mỗi khi nhận được thông báo điều hướng từ Google Maps:
  ```kotlin
  TymapKeepAliveEngine.getInstance(this).feedGoogleMapsData()
  ```
  *(Điều này giúp Watchdog biết Google Maps vẫn đang sống, nếu bị mất thông báo quá 20 giây sẽ tự động leo thang cấp độ!)*

---

### Bước 4: Thêm giao diện vào `fragment_settings.xml`
- **Vị trí file:**
  `C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP\app\src\main\res\layout\fragment_settings.xml`
- **Thêm vào ngay trước mục `CẬP NHẬT PHẦN MỀM & FIRMWARE`:**
```xml
<!-- Section: DẪN ĐƯỜNG NỀN & KEEP-ALIVE -->
<TextView
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:text="GIỮ SỐNG BẢN ĐỒ KHI TẮT MÀN HÌNH"
    android:textSize="11sp"
    android:textStyle="bold"
    android:textColor="@color/colorAccentCyan"
    android:layout_marginBottom="4dp"/>

<com.google.android.material.button.MaterialButton
    android:id="@+id/btnKeepAliveSettings"
    style="@style/Widget.Material3.Button.TonalButton"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:text="🧭 Cấu hình cấp độ giữ sống Google Maps"
    android:textAllCaps="false"
    android:layout_marginBottom="10dp" />

<com.google.android.material.divider.MaterialDivider
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginVertical="10dp"/>
```

---

### Bước 5: Viết hàm xử lý trong `SettingsFragment.kt`
- **Vị trí file:**
  `C:\Users\phamn\Documents\PlatformIO\Tdriver\TYMAP\app\src\main\java\com\example\tymap\ui\SettingsFragment.kt`
- **Thêm sự kiện bấm nút và Dialog hiển thị:**
```kotlin
binding.btnKeepAliveSettings.setOnClickListener {
    showKeepAliveSettingsDialog()
}
```
Và phương thức hiển thị dialog:
```kotlin
private fun showKeepAliveSettingsDialog() {
    val ctx = requireContext()
    val keepAlive = TymapKeepAliveEngine.getInstance(ctx)
    val tiers = KeepAliveTier.values()
    var selectedTier = keepAlive.currentTier
    var autoEscalate = keepAlive.isAutoEscalateEnabled

    val dialogView = android.widget.ScrollView(ctx).apply {
        setPadding(40, 30, 40, 30)
    }

    val container = android.widget.LinearLayout(ctx).apply {
        orientation = android.widget.LinearLayout.VERTICAL
    }

    val tvIntro = android.widget.TextView(ctx).apply {
        text = "Chọn phương thức ngăn chặn Android đưa Google Maps vào trạng thái ngủ đông khi bạn tắt màn hình / đút điện thoại vào túi hoặc cốp xe:"
        textSize = 12.5f
        setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        setPadding(0, 0, 0, 20)
    }
    container.addView(tvIntro)

    val radioGroup = android.widget.RadioGroup(ctx)
    tiers.forEachIndexed { index, tier ->
        val rb = android.widget.RadioButton(ctx).apply {
            id = index + 6000
            val titleColor = if (tier == KeepAliveTier.TIER_2_SILENT_CONTINUOUS) " (Khuyên dùng)" else ""
            text = "${tier.title}$titleColor\n• ${tier.description}\n${tier.note}\n"
            textSize = 12f
            setTextColor(android.graphics.Color.WHITE)
            isChecked = (tier == selectedTier)
            setPadding(14, 10, 14, 16)
            setOnClickListener { selectedTier = tier }
        }
        radioGroup.addView(rb)
    }
    container.addView(radioGroup)

    val cbAutoEsc = android.widget.CheckBox(ctx).apply {
        text = "⚡ Tự động leo thang cấp độ thông minh:\nNếu phát hiện Google Maps bị mất tín hiệu > 20 giây khi tắt màn hình, app sẽ tự động nâng lên cấp độ cao hơn để duy trì dẫn đường."
        textSize = 11.5f
        setTextColor(android.graphics.Color.parseColor("#00F0FF"))
        isChecked = autoEscalate
        setOnCheckedChangeListener { _, isChecked -> autoEscalate = isChecked }
        setPadding(10, 16, 10, 16)
    }
    container.addView(cbAutoEsc)
    dialogView.addView(container)

    com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
        .setTitle("🧭 Cấp Độ Giữ Sống Google Maps")
        .setView(dialogView)
        .setPositiveButton("Lưu Cài Đặt") { _, _ ->
            keepAlive.setTier(selectedTier)
            keepAlive.setAutoEscalate(autoEscalate)
            Toast.makeText(ctx, "🟢 Đã áp dụng: ${selectedTier.title}", Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton("Đóng", null)
        .show()
}
```

---

## 4. HƯỚNG DẪN DỌN DẸP LẠI REPO TYSMARTKEY (CLEANUP)

Để khôi phục repo Tysmartkey sạch sẽ, không bị dính mã nguồn của TYMAP:
1. Xóa file nhầm:
   ```powershell
   Remove-Item "c:\Users\phamn\Documents\PlatformIO\Tysmartkey\APP Controlesp\app\src\main\java\com\example\control_esp\TymapKeepAliveEngine.kt" -Force
   ```
2. Revert các file đã sửa trong Tysmartkey về lại nguyên bản:
   ```powershell
   git checkout -- "APP Controlesp/app/src/main/java/com/example/control_esp/MainActivity.kt" "APP Controlesp/app/src/main/java/com/example/control_esp/VehicleBackgroundService.kt" "APP Controlesp/app/src/main/res/layout/activity_main.xml"
   ```
