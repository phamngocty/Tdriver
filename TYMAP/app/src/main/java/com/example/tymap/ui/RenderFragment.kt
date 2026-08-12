package com.example.tymap.ui

import android.graphics.Bitmap
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.R
import com.example.tymap.databinding.FragmentRenderBinding
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.service.NavigationService
import com.example.tymap.utils.PrefsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RenderFragment : Fragment() {
    private var _binding: FragmentRenderBinding? = null
    private val binding get() = _binding!!
    private lateinit var logAdapter: LogAdapter
    private val logList = mutableListOf<String>()
    private var isLogPaused = false
    private var searchQuery = ""

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRenderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            v.setPadding(v.paddingLeft, insets.top, v.paddingRight, v.paddingBottom)
            windowInsets
        }
        setupLogRecyclerView()

        // 1. Observe last sent map image (fallback) and mapPreviewInfo (rolling crop simulation)
        lifecycleScope.launch {
            NavigationRepository.mapPreviewInfo.collectLatest { info ->
                if (info != null) {
                    if (info.croppedMap != null) {
                        binding.ivMapPreview.setImageBitmap(info.croppedMap)
                        binding.ivMapPreview.visibility = View.VISIBLE
                    }
                    if (info.fullMap != null) {
                        binding.ivFullMapPreview.setImageBitmap(info.fullMap)
                        binding.ivFullMapPreview.setCropInfo(info.cropX, info.cropY, info.cropSize)
                        binding.ivFullMapPreview.visibility = View.VISIBLE
                    } else {
                        binding.ivFullMapPreview.setImageDrawable(null)
                        binding.ivFullMapPreview.visibility = View.GONE
                    }
                } else {
                    // Fallback to last sent map image from BLE decode
                    val fallbackBmp = NavigationRepository.lastSentMapImage.value
                    if (fallbackBmp != null) {
                        binding.ivMapPreview.setImageBitmap(fallbackBmp)
                        binding.ivMapPreview.visibility = View.VISIBLE
                    } else {
                        binding.ivMapPreview.setImageDrawable(null)
                    }
                    binding.ivFullMapPreview.setImageDrawable(null)
                    binding.ivFullMapPreview.visibility = View.GONE
                }
            }
        }

        lifecycleScope.launch {
            NavigationRepository.lastSentMapImage.collectLatest { bitmap ->
                if (bitmap != null && NavigationRepository.mapPreviewInfo.value == null) {
                    binding.ivMapPreview.setImageBitmap(bitmap)
                    binding.ivMapPreview.visibility = View.VISIBLE
                }
            }
        }

        // 2. Observe navigation HUD preview data (for turn icon and details)
        lifecycleScope.launch {
            NavigationRepository.hudPreviewData.collectLatest { hud ->
                if (hud != null) {
                    if (hud.bitmapIcon != null) {
                        binding.ivIconPreview.setImageBitmap(hud.bitmapIcon)
                        binding.ivIconPreview.visibility = View.VISIBLE
                    } else if (hud.iconIndex >= 0) {
                        val iconRes = maneuverIconRes(hud.iconIndex)
                        binding.ivIconPreview.setImageResource(iconRes)
                        binding.ivIconPreview.visibility = View.VISIBLE
                    } else {
                        binding.ivIconPreview.visibility = View.GONE
                    }
                } else {
                    binding.ivIconPreview.visibility = View.GONE
                }
            }
        }

        // 3. Observe currently prepared/sending BLE packet
        lifecycleScope.launch {
            NavigationRepository.preparedBleData.collectLatest { data ->
                if (data.isNotEmpty()) {
                    binding.tvBlePacketData.text = data
                } else {
                    binding.tvBlePacketData.text = "Chưa có gói dữ liệu nào được kết xuất..."
                }
            }
        }

        // 4. Observe system BLE logs
        lifecycleScope.launch {
            NavigationRepository.logs.collectLatest { logs ->
                if (!isLogPaused) {
                    logList.clear()
                    val filtered = if (searchQuery.isEmpty()) {
                        logs
                    } else {
                        logs.filter { it.contains(searchQuery, ignoreCase = true) }
                    }
                    logList.addAll(filtered)
                    logAdapter.notifyDataSetChanged()
                    if (logList.isNotEmpty()) {
                        binding.rvRenderLogs.scrollToPosition(0)
                    }
                }
            }
        }
 
        // 5. Setup Action Buttons
        binding.btnSendCurrentMap.setOnClickListener {
            sendCurrentMapImage()
        }
 
        binding.btnSendDemoNav.setOnClickListener {
            sendDemoNavigationData()
        }
 
        binding.btnClearRenderLogs.setOnClickListener {
            NavigationRepository.clearLogs()
            Toast.makeText(requireContext(), "Đã xóa lịch sử log", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearRenderLogsTab.setOnClickListener {
            NavigationRepository.clearLogs()
            Toast.makeText(requireContext(), "Đã xóa lịch sử log", Toast.LENGTH_SHORT).show()
        }

        binding.btnPauseRenderLog.setOnClickListener {
            isLogPaused = !isLogPaused
            binding.btnPauseRenderLog.text = if (isLogPaused) "Tiếp tục cuộn" else "Tạm dừng cuộn"
            binding.btnPauseRenderLog.setIconResource(if (isLogPaused) R.drawable.ic_play else R.drawable.ic_stop)
            if (!isLogPaused) {
                val logs = NavigationRepository.logs.value
                logList.clear()
                val filtered = if (searchQuery.isEmpty()) {
                    logs
                } else {
                    logs.filter { it.contains(searchQuery, ignoreCase = true) }
                }
                logList.addAll(filtered)
                logAdapter.notifyDataSetChanged()
                if (logList.isNotEmpty()) {
                    binding.rvRenderLogs.scrollToPosition(0)
                }
            }
        }

        binding.etSearchRenderLog.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchQuery = s?.toString() ?: ""
                val logs = NavigationRepository.logs.value
                logList.clear()
                val filtered = if (searchQuery.isEmpty()) {
                    logs
                } else {
                    logs.filter { it.contains(searchQuery, ignoreCase = true) }
                }
                logList.addAll(filtered)
                logAdapter.notifyDataSetChanged()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun setupLogRecyclerView() {
        logAdapter = LogAdapter(logList)
        binding.rvRenderLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRenderLogs.adapter = logAdapter
    }

    private fun sendCurrentMapImage() {
        val service = NavigationService.activeInstance
        val bleManager = NavigationService.bleManager
        if (service != null && bleManager != null && bleManager.isConnected) {
            lifecycleScope.launch(Dispatchers.IO) {
                val quality = PrefsHelper.getFloat(requireContext(), "jpeg_quality", 70f).toInt()
                val jpeg = service.renderOsmMap(quality)
                withContext(Dispatchers.Main) {
                    if (jpeg != null) {
                        service.sendImageToDevice(jpeg)
                        Toast.makeText(requireContext(), "Đã gửi ảnh bản đồ hiện tại", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Không thể chụp bản đồ lúc này", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            Toast.makeText(requireContext(), "Vui lòng kết nối ESP32 và đảm bảo Service đang chạy", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendDemoNavigationData() {
        val bleManager = NavigationService.bleManager
        if (bleManager != null && bleManager.isConnected) {
            val demoData = "active=1\nnav=1\ndist=350m\ntitle=Rẽ trái vào Nguyễn Huệ\nroad=Nguyễn Huệ\ndir=5\neta=18:30\nete=5 min"
            bleManager.writeNavigationData(demoData)
            
            // Cập nhật lên UI
            val mockHud = NavigationRepository.HudData(
                active = true,
                isNavigation = true,
                distance = "350m",
                duration = "5 min",
                eta = "18:30",
                title = "Rẽ trái vào Nguyễn Huệ",
                directions = "Nguyễn Huệ",
                iconIndex = 5
            )
            NavigationRepository.updateHudPreview(mockHud)
            Toast.makeText(requireContext(), "Đã gửi gói tin chỉ đường demo", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "ESP32 chưa kết nối", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // Nested Recycler Log Adapter for debug logs
    private class LogAdapter(private val logs: List<String>) :
        RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

        class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvLog: TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            // Cấu hình hiển thị chữ nhỏ và màu xanh dương/xám trên nền tối
            val tv = view.findViewById<TextView>(android.R.id.text1)
            tv.setTextColor(0xFFCCCCCC.toInt())
            tv.textSize = 12f
            tv.setPadding(4, 4, 4, 4)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            holder.tvLog.text = logs[position]
        }

        override fun getItemCount(): Int = logs.size
    }
}
