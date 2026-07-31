package com.example.tymap.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tymap.R
import com.example.tymap.databinding.FragmentNotificationsBinding
import com.example.tymap.service.NavigationService
import com.example.tymap.utils.PrefsHelper
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

class NotificationsFragment : Fragment() {
    private var _binding: FragmentNotificationsBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: NotificationAppAdapter
    private val fullAppList = mutableListOf<NotificationApp>()
    private val filteredAppList = mutableListOf<NotificationApp>()
    private var selectedAppPreset: String = "Zalo"

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processAndSendGalleryImage(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNotificationsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        checkPermissionStatus()
        setupPresetChips()
        setupSearchFilter()

        binding.btnGrantPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        binding.btnSendManual.setOnClickListener {
            sendManualNotification()
        }

        binding.btnSendLiveMap.setOnClickListener {
            sendLiveMapFrame()
        }

        binding.btnPickGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnSendGmapsCapture.setOnClickListener {
            sendGmapsCaptureFrame()
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionStatus()
    }

    private fun checkPermissionStatus() {
        val context = context ?: return
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val isGranted = !flat.isNullOrEmpty() && flat.contains(context.packageName)

        if (isGranted) {
            binding.ivPermissionStatus.setImageResource(R.drawable.ic_check)
            binding.ivPermissionStatus.setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
            binding.tvPermissionTitle.text = "Dịch vụ đọc thông báo"
            binding.tvPermissionDesc.text = "Đã sẵn sàng đồng bộ thông báo sang ESP32"
            binding.btnGrantPermission.visibility = View.GONE
            binding.cardPermissionStatus.strokeColor = android.graphics.Color.parseColor("#4CAF50")
        } else {
            binding.ivPermissionStatus.setImageResource(R.drawable.ic_settings)
            binding.ivPermissionStatus.setColorFilter(android.graphics.Color.parseColor("#F44336"))
            binding.tvPermissionTitle.text = "Chưa cấp quyền truy cập thông báo!"
            binding.tvPermissionDesc.text = "Vui lòng cấp quyền để đồng hồ nhận tin nhắn từ Zalo, Messenger, SMS..."
            binding.btnGrantPermission.visibility = View.VISIBLE
            binding.cardPermissionStatus.strokeColor = android.graphics.Color.parseColor("#F44336")
        }
    }

    private fun setupPresetChips() {
        binding.chipZalo.setOnClickListener {
            selectedAppPreset = "Zalo"
            binding.etManualNotif.setText("Chiều nay đi cafe nhé bạn!")
        }
        binding.chipMessenger.setOnClickListener {
            selectedAppPreset = "Messenger"
            binding.etManualNotif.setText("Bạn nhận được 1 tin nhắn mới")
        }
        binding.chipSMS.setOnClickListener {
            selectedAppPreset = "SMS"
            binding.etManualNotif.setText("Ma OTP gia dich la 839201")
        }
        binding.chipCall.setOnClickListener {
            selectedAppPreset = "Cuộc gọi"
            binding.etManualNotif.setText("Cuoc goi den tu 0912345678")
        }
    }

    private fun setupRecyclerView() {
        val context = context ?: return
        val pm = context.packageManager
        val enabledApps = PrefsHelper.getStringSet(context, "enabled_notifications", setOf(
            "com.android.server.telecom", "com.google.android.apps.messaging", "com.zing.zalo"
        )).toMutableSet()

        lifecycleScope.launch(Dispatchers.IO) {
            val appMap = mutableMapOf<String, NotificationApp>()

            // 1. Quét tất cả gói ứng dụng đã cài đặt trên thiết bị
            try {
                val packages = pm.getInstalledPackages(PackageManager.GET_META_DATA)
                packages.forEach { pkgInfo ->
                    val pkg = pkgInfo.packageName
                    if (pkg != context.packageName) {
                        val appInfo = pkgInfo.applicationInfo
                        val name = if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else pkg
                        val icon = if (appInfo != null) {
                            try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { pm.defaultActivityIcon }
                        } else pm.defaultActivityIcon
                        val isEnabled = enabledApps.contains(pkg)
                        appMap[pkg] = NotificationApp(pkg, name, icon, isEnabled)
                    }
                }
            } catch (e: Exception) {
                val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                apps.forEach { appInfo ->
                    val pkg = appInfo.packageName
                    if (pkg != context.packageName) {
                        val name = pm.getApplicationLabel(appInfo).toString()
                        val icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { pm.defaultActivityIcon }
                        val isEnabled = enabledApps.contains(pkg)
                        appMap[pkg] = NotificationApp(pkg, name, icon, isEnabled)
                    }
                }
            }

            // 2. Đảm bảo các app đã bật luôn có mặt trong danh sách kể cả khi hệ điều hành ẩn
            enabledApps.forEach { pkg ->
                if (!appMap.containsKey(pkg) && pkg != context.packageName) {
                    val name = try {
                        val ai = pm.getApplicationInfo(pkg, 0)
                        pm.getApplicationLabel(ai).toString()
                    } catch (e: Exception) {
                        if (pkg.contains("zalo", ignoreCase = true)) "Zalo" else pkg
                    }
                    val icon = try {
                        pm.getApplicationIcon(pkg)
                    } catch (e: Exception) { pm.defaultActivityIcon }
                    appMap[pkg] = NotificationApp(pkg, name, icon, true)
                }
            }

            // 3. Sắp xếp: Các ứng dụng ĐÃ BẬT nằm lên đầu tiên, sau đó sắp xếp theo thứ tự bảng chữ cái
            val list = appMap.values.toList()
                .sortedWith(compareByDescending<NotificationApp> { it.isEnabled }.thenBy { it.name.lowercase() })

            fullAppList.clear()
            fullAppList.addAll(list)

            withContext(Dispatchers.Main) {
                val enabledCount = fullAppList.count { it.isEnabled }
                binding.tvAppsCount.text = "Tất cả ứng dụng (${fullAppList.size} app • Đã bật $enabledCount app)"
                filteredAppList.clear()
                filteredAppList.addAll(fullAppList)

                adapter = NotificationAppAdapter(filteredAppList, AdapterMode.MANAGE) { pkg, isChecked ->
                    val set = PrefsHelper.getStringSet(context, "enabled_notifications", emptySet()).toMutableSet()
                    if (isChecked) {
                        set.add(pkg)
                    } else {
                        set.remove(pkg)
                    }
                    PrefsHelper.putStringSet(context, "enabled_notifications", set)

                    val found = fullAppList.find { it.packageName == pkg }
                    found?.isEnabled = isChecked
                    val count = fullAppList.count { it.isEnabled }
                    binding.tvAppsCount.text = "Tất cả ứng dụng (${fullAppList.size} app • Đã bật $count app)"
                }

                binding.rvNotificationApps.layoutManager = LinearLayoutManager(context)
                binding.rvNotificationApps.adapter = adapter
            }
        }
    }

    private fun setupSearchFilter() {
        binding.etSearchApp.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApps(s.toString().trim())
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun filterApps(query: String) {
        if (query.isEmpty()) {
            filteredAppList.clear()
            filteredAppList.addAll(fullAppList)
        } else {
            val lower = query.lowercase()
            filteredAppList.clear()
            filteredAppList.addAll(fullAppList.filter {
                it.name.lowercase().contains(lower) || it.packageName.lowercase().contains(lower)
            })
        }
        if (::adapter.isInitialized) {
            adapter.updateList(filteredAppList)
        }
    }

    private fun sendManualNotification() {
        val text = binding.etManualNotif.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "Vui lòng nhập văn bản", Toast.LENGTH_SHORT).show()
            return
        }

        val json = mapOf(
            "app" to selectedAppPreset,
            "title" to "Thử nghiệm",
            "message" to text
        )
        
        val bleManager = NavigationService.bleManager
        if (bleManager != null) {
            bleManager.writeNotification(Gson().toJson(json))
            binding.etManualNotif.text?.clear()
            Toast.makeText(requireContext(), "Đã gửi thông báo $selectedAppPreset sang đồng hồ", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "ESP32 chưa kết nối", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendLiveMapFrame() {
        val bleManager = NavigationService.bleManager
        val service = NavigationService.activeInstance
        
        if (bleManager != null && bleManager.isConnected && service != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                val quality = PrefsHelper.getFloat(requireContext(), "jpeg_quality", 70f).toInt()
                val jpeg = service.renderOsmMap(quality)
                
                withContext(Dispatchers.Main) {
                    if (jpeg != null) {
                        service.sendImageToDevice(jpeg)
                        Toast.makeText(requireContext(), "Đã gửi ảnh bản đồ", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Không thể chụp ảnh bản đồ lúc này", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(requireContext(), "Vui lòng kết nối ESP32", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processAndSendGalleryImage(uri: Uri) {
        val bleManager = NavigationService.bleManager
        val service = NavigationService.activeInstance
        if (bleManager == null || !bleManager.isConnected || service == null) {
            Toast.makeText(requireContext(), "Vui lòng kết nối ESP32", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap != null) {
                    val width = originalBitmap.width
                    val height = originalBitmap.height
                    val size = Math.min(width, height)
                    val x = (width - size) / 2
                    val y = (height - size) / 2
                    
                    val cropped = Bitmap.createBitmap(originalBitmap, x, y, size, size)
                    val scaled = Bitmap.createScaledBitmap(cropped, 240, 240, true)
                    
                    val out = ByteArrayOutputStream()
                    val quality = PrefsHelper.getFloat(requireContext(), "jpeg_quality", 80f).toInt()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    val jpegBytes = out.toByteArray()
                    
                    originalBitmap.recycle()
                    if (cropped != scaled) cropped.recycle()
                    scaled.recycle()

                    withContext(Dispatchers.Main) {
                        service.sendImageToDevice(jpegBytes)
                        Toast.makeText(requireContext(), "Đã gửi ảnh từ máy", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Lỗi xử lý ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun sendGmapsCaptureFrame() {
        val bleManager = NavigationService.bleManager
        val service = NavigationService.activeInstance
        val captureManager = service?.screenCaptureManager

        if (bleManager == null || !bleManager.isConnected || service == null) {
            Toast.makeText(requireContext(), "Vui lòng kết nối ESP32", Toast.LENGTH_SHORT).show()
            return
        }

        if (captureManager == null) {
            Toast.makeText(requireContext(), "Chưa khởi tạo trình chụp Google Maps. Vui lòng cấp quyền chụp ở cài đặt trước.", Toast.LENGTH_LONG).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val quality = PrefsHelper.getFloat(requireContext(), "jpeg_quality", 70f).toInt()
            val jpeg = captureManager.captureAndProcess(quality, "gmaps_")
            
            withContext(Dispatchers.Main) {
                if (jpeg != null) {
                    service.sendImageToDevice(jpeg)
                    Toast.makeText(requireContext(), "Đã gửi ảnh chụp Google Maps", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "Không lấy được ảnh chụp (Google Maps có thể đang chạy ngầm hoặc cần cấp lại quyền)", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
