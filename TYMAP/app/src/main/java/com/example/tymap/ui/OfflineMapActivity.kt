package com.example.tymap.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.R
import com.example.tymap.databinding.ActivityOfflineMapBinding
import com.example.tymap.databinding.ItemOfflineRegionBinding
import com.example.tymap.repository.NavigationRepository
import com.example.tymap.service.OfflineDownloadService
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

class OfflineMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfflineMapBinding
    private lateinit var adapter: OfflineRegionAdapter
    private val gson = Gson()
    
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                OfflineDownloadService.BROADCAST_PROGRESS -> {
                    val progress = intent.getIntExtra("PROGRESS", 0)
                    val downloaded = intent.getIntExtra("DOWNLOADED", 0)
                    val total = intent.getIntExtra("TOTAL", 0)
                    val bytes = intent.getLongExtra("BYTES", 0L)
                    
                    binding.progressCard.visibility = View.VISIBLE
                    binding.pbDownload.progress = progress
                    binding.tvProgressPercent.text = "$progress%"
                    binding.tvProgressBytes.text = "$downloaded / $total tiles (${String.format("%.1f", bytes.toFloat() / 1024 / 1024)} MB)"
                }
                OfflineDownloadService.BROADCAST_COMPLETE -> {
                    binding.progressCard.visibility = View.GONE
                    loadRegions()
                    Toast.makeText(this@OfflineMapActivity, "Tải bản đồ ngoại tuyến thành công!", Toast.LENGTH_SHORT).show()
                }
                OfflineDownloadService.BROADCAST_ERROR -> {
                    val message = intent.getStringExtra("MESSAGE") ?: "Lỗi không xác định"
                    binding.progressCard.visibility = View.GONE
                    Toast.makeText(this@OfflineMapActivity, message, Toast.LENGTH_LONG).show()
                }
                OfflineDownloadService.BROADCAST_NEED_CONFIRM -> {
                    showMobileDataConfirmDialog()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfflineMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)

        setupRecyclerView()
        setupListeners()
        loadRegions()
        
        // Register receiver for download progress
        val filter = IntentFilter().apply {
            addAction(OfflineDownloadService.BROADCAST_PROGRESS)
            addAction(OfflineDownloadService.BROADCAST_COMPLETE)
            addAction(OfflineDownloadService.BROADCAST_ERROR)
            addAction(OfflineDownloadService.BROADCAST_NEED_CONFIRM)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupRecyclerView() {
        adapter = OfflineRegionAdapter()
        binding.rvRegions.layoutManager = LinearLayoutManager(this)
        binding.rvRegions.adapter = adapter
    }

    private fun setupListeners() {
        binding.fabAdd.setOnClickListener {
            // Đặt chế độ chọn vùng bản đồ
            NavigationRepository.setOfflineSelectionMode(true)
            Toast.makeText(this, "Hãy chọn vùng bản đồ cần tải trên màn hình chính", Toast.LENGTH_LONG).show()
            finish()
        }

        binding.btnCancelDownload.setOnClickListener {
            val intent = Intent(this, OfflineDownloadService::class.java).apply {
                action = OfflineDownloadService.ACTION_CANCEL_DOWNLOAD
            }
            startService(intent)
            binding.progressCard.visibility = View.GONE
            Toast.makeText(this, "Đã hủy tiến trình tải", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadRegions() {
        val configFile = File(getExternalFilesDir(null), "offline_maps/regions.json")
        if (configFile.exists()) {
            try {
                val listType = object : TypeToken<List<Map<String, Any>>>() {}.type
                val list: List<Map<String, Any>> = gson.fromJson(configFile.readText(), listType)
                if (list.isEmpty()) {
                    binding.rvRegions.visibility = View.GONE
                    binding.tvNoData.visibility = View.VISIBLE
                } else {
                    binding.rvRegions.visibility = View.VISIBLE
                    binding.tvNoData.visibility = View.GONE
                    adapter.setItems(list)
                }
            } catch (e: Exception) {
                binding.rvRegions.visibility = View.GONE
                binding.tvNoData.visibility = View.VISIBLE
            }
        } else {
            binding.rvRegions.visibility = View.GONE
            binding.tvNoData.visibility = View.VISIBLE
        }
    }

    private fun showMobileDataConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle("Xác nhận tải mạng di động")
            .setMessage("Thiết bị không kết nối Wifi. Bạn có đồng ý tải bản đồ bằng dữ liệu di động (3G/4G)?")
            .setPositiveButton("Đồng ý") { _, _ ->
                val intent = Intent(this, OfflineDownloadService::class.java).apply {
                    action = OfflineDownloadService.ACTION_CONFIRM_MOBILE_DATA
                }
                startService(intent)
            }
            .setNegativeButton("Hủy bỏ") { _, _ ->
                val intent = Intent(this, OfflineDownloadService::class.java).apply {
                    action = OfflineDownloadService.ACTION_CANCEL_DOWNLOAD
                }
                startService(intent)
                binding.progressCard.visibility = View.GONE
            }
            .setCancelable(false)
            .show()
    }

    private fun deleteRegion(regionId: String) {
        AlertDialog.Builder(this)
            .setTitle("Xóa vùng bản đồ")
            .setMessage("Bạn có chắc chắn muốn xóa vùng bản đồ ngoại tuyến này?")
            .setPositiveButton("Xóa") { _, _ ->
                val regionDir = File(getExternalFilesDir(null), "offline_maps/$regionId")
                if (regionDir.exists()) {
                    regionDir.deleteRecursively()
                }

                // Cập nhật file JSON
                val configFile = File(getExternalFilesDir(null), "offline_maps/regions.json")
                if (configFile.exists()) {
                    try {
                        val listType = object : TypeToken<MutableList<Map<String, Any>>>() {}.type
                        val list: MutableList<Map<String, Any>> = gson.fromJson(configFile.readText(), listType)
                        list.removeAll { it["id"] == regionId }
                        configFile.writeText(gson.toJson(list))
                    } catch (e: Exception) {}
                }

                loadRegions()
                Toast.makeText(this, "Đã xóa vùng bản đồ ngoại tuyến", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun updateRegion(regionData: Map<String, Any>) {
        val regionId = regionData["id"] as String
        val regionName = regionData["name"] as String
        val minZoom = (regionData["minZoom"] as Double).toInt()
        val maxZoom = (regionData["maxZoom"] as Double).toInt()
        
        val bbox = regionData["boundingBox"] as Map<*, *>
        val minLat = bbox["minLat"] as Double
        val maxLat = bbox["maxLat"] as Double
        val minLon = bbox["minLon"] as Double
        val maxLon = bbox["maxLon"] as Double
        val tileSourceIndex = (regionData["tileSourceIndex"] as? Double)?.toInt() ?: 0

        val zooms = (minZoom..maxZoom).toList().toIntArray()

        val intent = Intent(this, OfflineDownloadService::class.java).apply {
            action = OfflineDownloadService.ACTION_START_DOWNLOAD
            putExtra("REGION_ID", regionId)
            putExtra("REGION_NAME", regionName)
            putExtra("ZOOMS", zooms)
            putExtra("MIN_LAT", minLat)
            putExtra("MAX_LAT", maxLat)
            putExtra("MIN_LON", minLon)
            putExtra("MAX_LON", maxLon)
            putExtra("TILE_SOURCE_INDEX", tileSourceIndex)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.progressCard.visibility = View.VISIBLE
        binding.pbDownload.progress = 0
        binding.tvProgressPercent.text = "0%"
        binding.tvProgressBytes.text = "Đang kết nối..."
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {}
    }

    // RecyclerView Adapter
    inner class OfflineRegionAdapter : RecyclerView.Adapter<OfflineRegionAdapter.ViewHolder>() {

        private var items: List<Map<String, Any>> = emptyList()

        fun setItems(newItems: List<Map<String, Any>>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemOfflineRegionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ViewHolder(private val itemBinding: ItemOfflineRegionBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(item: Map<String, Any>) {
                val name = item["name"] as? String ?: "Vùng bản đồ"
                val date = item["date"] as? String ?: ""
                val minZoom = (item["minZoom"] as? Double)?.toInt() ?: 14
                val maxZoom = (item["maxZoom"] as? Double)?.toInt() ?: 16
                val tilesCount = (item["tilesCount"] as? Double)?.toInt() ?: 0
                val sizeBytes = (item["sizeBytes"] as? Double)?.toLong() ?: 0L

                itemBinding.tvRegionName.text = name
                itemBinding.tvRegionDate.text = date
                
                val sizeMb = sizeBytes.toFloat() / 1024 / 1024
                itemBinding.tvRegionDetails.text = "Zoom: $minZoom-$maxZoom | Tiles: $tilesCount | Dung lượng: ${String.format("%.1f", sizeMb)} MB"

                itemBinding.btnDelete.setOnClickListener {
                    val id = item["id"] as? String ?: return@setOnClickListener
                    deleteRegion(id)
                }

                itemBinding.btnUpdate.setOnClickListener {
                    updateRegion(item)
                }
            }
        }
    }
}
