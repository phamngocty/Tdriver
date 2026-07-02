package com.example.tymap.ui

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
    private val appList = mutableListOf<NotificationApp>()

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
        
        binding.btnSendManual.setOnClickListener {
            sendManualNotification()
        }

        binding.btnAddApp.setOnClickListener {
            showAddAppDialog()
        }

        binding.btnSendLiveMap.setOnClickListener {
            sendLiveMapFrame()
        }

        binding.btnPickGallery.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
    }

    private fun setupRecyclerView() {
        val context = requireContext()
        val pm = context.packageManager
        val enabledApps = PrefsHelper.getStringSet(context, "enabled_notifications", setOf(
            "com.android.server.telecom", "com.google.android.apps.messaging", "com.zing.zalo"
        )).toMutableSet()

        appList.clear()
        enabledApps.forEach { pkg ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                appList.add(NotificationApp(
                    pkg,
                    pm.getApplicationLabel(appInfo).toString(),
                    pm.getApplicationIcon(appInfo),
                    true
                ))
            } catch (e: Exception) {}
        }
        
        appList.sortBy { it.name.lowercase() }

        adapter = NotificationAppAdapter(appList, AdapterMode.MANAGE) { pkg, _ ->
            enabledApps.remove(pkg)
            PrefsHelper.putStringSet(context, "enabled_notifications", enabledApps)
            val index = appList.indexOfFirst { it.packageName == pkg }
            if (index != -1) {
                appList.removeAt(index)
                adapter.notifyItemRemoved(index)
            }
        }
        
        binding.rvNotificationApps.layoutManager = LinearLayoutManager(context)
        binding.rvNotificationApps.adapter = adapter
    }

    private fun sendManualNotification() {
        val text = binding.etManualNotif.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(requireContext(), "Vui lòng nhập văn bản", Toast.LENGTH_SHORT).show()
            return
        }

        val json = mapOf(
            "app" to "Manual",
            "title" to "Tin nhắn",
            "message" to text
        )
        
        val bleManager = NavigationService.bleManager
        if (bleManager != null) {
            bleManager.writeNotification(Gson().toJson(json))
            binding.etManualNotif.text?.clear()
            Toast.makeText(requireContext(), "Đã gửi tin nhắn", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "ESP32 chưa kết nối", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendLiveMapFrame() {
        val bleManager = NavigationService.bleManager
        val screenCaptureManager = NavigationService.screenCaptureManager
        
        if (bleManager != null && bleManager.isConnected) {
            lifecycleScope.launch(Dispatchers.IO) {
                val quality = PrefsHelper.getFloat(requireContext(), "jpeg_quality", 70f).toInt()
                val jpeg = screenCaptureManager?.captureAndProcess(quality, "map_tab_")
                
                withContext(Dispatchers.Main) {
                    if (jpeg != null) {
                        lifecycleScope.launch {
                            bleManager.writeMapImage(jpeg)
                            Toast.makeText(requireContext(), "Đã gửi ảnh bản đồ", Toast.LENGTH_SHORT).show()
                        }
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
        if (bleManager == null || !bleManager.isConnected) {
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
                        lifecycleScope.launch {
                            bleManager.writeMapImage(jpegBytes)
                            Toast.makeText(requireContext(), "Đã gửi ảnh từ máy", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Lỗi xử lý ảnh: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showAddAppDialog() {
        val context = requireContext()
        val pm = context.packageManager
        val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        val enabledApps = PrefsHelper.getStringSet(context, "enabled_notifications", emptySet()).toMutableSet()
        
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_app, null)
        val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvAllApps)
        val sv = dialogView.findViewById<androidx.appcompat.widget.SearchView>(R.id.svApps)

        val fullAppList = installedApps.map { 
            NotificationApp(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it), enabledApps.contains(it.packageName))
        }

        val addAdapter = NotificationAppAdapter(fullAppList, AdapterMode.SELECT) { pkg, enabled ->
            if (enabled) enabledApps.add(pkg) else enabledApps.remove(pkg)
        }
        
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = addAdapter

        sv.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                val filtered = fullAppList.filter { it.name.contains(newText ?: "", ignoreCase = true) }
                addAdapter.updateList(filtered)
                return true
            }
        })

        AlertDialog.Builder(context)
            .setTitle("Thêm ứng dụng thông báo")
            .setView(dialogView)
            .setPositiveButton("Xong") { _, _ ->
                PrefsHelper.putStringSet(context, "enabled_notifications", enabledApps)
                setupRecyclerView()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
