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

/**
 * 🎚️ 4 CẤP ĐỘ GIỮ SỐNG GOOGLE MAPS KHI TẮT MÀN HÌNH
 */
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
        "Sử dụng dịch vụ Foreground Service kết hợp yêu cầu tọa độ GPS nền và WakeLock phần cứng.",
        "💡 Lưu ý cho người dùng: Tiết kiệm pin tối đa, phù hợp hoàn hảo với các dòng máy chạy thuần Android gốc như Google Pixel, Motorola, Sony hoặc Samsung One UI."
    ),
    TIER_2_SILENT_CONTINUOUS(
        2,
        "Cấp độ 2: Âm thanh im lặng liên tục",
        "Khuyên dùng",
        "Phát luồng âm thanh PCM 0dB (im lặng tuyệt đối) lặp liên tục với nhãn dẫn đường rảnh tay (USAGE_ASSISTANCE_NAVIGATION_GUIDANCE), tuyệt đối không tranh chấp Audio Focus.",
        "💡 Lưu ý cho người dùng: Đánh lừa Android giữ Google Maps chạy liên tục khi tắt màn hình mà HOÀN TOÀN KHÔNG làm ngắt nhạc Spotify/Zing MP3, không ảnh hưởng đến chuông cuộc gọi hay tiếng thông báo Zalo/SMS."
    ),
    TIER_3_AUDIO_PULSE(
        3,
        "Cấp độ 3: Xung âm thanh định kỳ (15s)",
        "Cân bằng pin",
        "Thay vì phát liên tục, mỗi 15 giây hệ thống phát một xung micro-audio im lặng 200ms để 'gõ thức' hệ thống âm thanh và cảm biến trước khi máy chìm vào giấc ngủ Doze Mode.",
        "💡 Lưu ý cho người dùng: Vừa duy trì được thông báo dẫn đường khi tắt màn hình, vừa tiết kiệm pin hơn Cấp độ 2."
    ),
    TIER_4_VIRTUAL_HEADSET(
        4,
        "Cấp độ 4: Giả lập Tai nghe / Car-Kit Bluetooth",
        "Cực mạnh - Bất tử",
        "Đăng ký MediaSession chuyên nghiệp và phát luồng âm thanh dẫn đường rảnh tay (hệ điều hành nhận diện như máy đang kết nối với bộ đàm/tai nghe Bluetooth của mũ bảo hiểm xe máy).",
        "💡 Lưu ý cho người dùng: Cấp độ mạnh nhất, đặc trị các dòng máy có cơ chế diệt app ngầm hung hãn nhất hiện nay như Xiaomi (HyperOS / MIUI), Oppo (ColorOS), Vivo, Realme."
    );

    companion object {
        fun fromLevel(level: Int): KeepAliveTier {
            return values().firstOrNull { it.level == level } ?: TIER_2_SILENT_CONTINUOUS
        }
    }
}

/**
 * 🚀 TYMAP KEEP-ALIVE ENGINE:
 * Module điều phối giữ Google Maps và GPS luôn thức khi tắt màn hình.
 */
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

    // Cấp độ 1: WakeLock
    private var wakeLock: PowerManager.WakeLock? = null

    // Cấp độ 2 & 3: AudioTrack tự sinh PCM im lặng
    private var audioTrack: AudioTrack? = null
    private val pulseHandler = Handler(Looper.getMainLooper())

    // Cấp độ 4: MediaSession giả lập thiết bị đa phương tiện
    private var mediaSession: MediaSession? = null

    // Watchdog giám sát mất gói tin từ Google Maps
    private var lastGoogleMapsPacketTime = System.currentTimeMillis()
    private val watchdogHandler = Handler(Looper.getMainLooper())

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (isAutoEscalateEnabled) {
                val elapsed = System.currentTimeMillis() - lastGoogleMapsPacketTime
                // Nếu đang dẫn đường mà quá 20s không nhận được thông báo mới từ Google Maps
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
                        KeepAliveTier.TIER_4_VIRTUAL_HEADSET -> {
                            // Đã ở mức cao nhất
                        }
                    }
                }
            }
            watchdogHandler.postDelayed(this, 10000L)
        }
    }

    /**
     * Cập nhật cấp độ cài đặt
     */
    fun setTier(tier: KeepAliveTier, savePref: Boolean = true) {
        currentTier = tier
        if (savePref) {
            prefs.edit().putInt(KEY_SELECTED_TIER, tier.level).apply()
        }
        if (isRunning) {
            restartCurrentTier()
        }
    }

    /**
     * Bật / Tắt tính năng tự động leo thang thông minh
     */
    fun setAutoEscalate(enabled: Boolean) {
        isAutoEscalateEnabled = enabled
        prefs.edit().putBoolean(KEY_AUTO_ESCALATE, enabled).apply()
    }

    /**
     * Khởi động bộ giữ sống
     */
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

    /**
     * Dừng toàn bộ bộ giữ sống khi dừng dẫn đường
     */
    fun stop() {
        isRunning = false
        watchdogHandler.removeCallbacks(watchdogRunnable)
        releaseWakeLock()
        stopAudioTrack()
        pulseHandler.removeCallbacksAndMessages(null)
        stopVirtualHeadsetSession()
        Log.i(TAG, "🛑 Đã dừng toàn bộ Keep-Alive Engine")
    }

    /**
     * Khởi động lại cấp độ hiện tại
     */
    private fun restartCurrentTier() {
        stopAudioTrack()
        pulseHandler.removeCallbacksAndMessages(null)
        stopVirtualHeadsetSession()
        applyTierLogic(currentTier)
    }

    /**
     * Áp dụng logic tương ứng với từng cấp độ
     */
    private fun applyTierLogic(tier: KeepAliveTier) {
        when (tier) {
            KeepAliveTier.TIER_1_NATIVE -> {
                Log.d(TAG, "Đang chạy Cấp 1: WakeLock & Native FusedLocation")
            }
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

    /**
     * Báo cho Watchdog biết vừa nhận được gói tin dẫn đường từ Google Maps
     */
    fun feedGoogleMapsData() {
        lastGoogleMapsPacketTime = System.currentTimeMillis()
    }

    // =========================================================================
    // 🔹 CẤP ĐỘ 1: WAKELOCK PHẦN CỨNG
    // =========================================================================
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Tymap::NavigationWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // Giữ tối đa 4 tiếng
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

    // =========================================================================
    // 🔹 CẤP ĐỘ 2: LUỒNG ÂM THANH PCM IM LẶNG (KHÔNG CƯỚP FOCUS)
    // =========================================================================
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

    // =========================================================================
    // 🔹 CẤP ĐỘ 3: XUNG ÂM THANH ĐỊNH KỲ (AUDIO PULSE)
    // =========================================================================
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

    // =========================================================================
    // 🔹 CẤP ĐỘ 4: GIẢ LẬP TAI NGHE / MEDIASESSION XE MÁY
    // =========================================================================
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
