package com.example.tymap.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.databinding.DialogManageSavedDevicesBinding
import com.example.tymap.databinding.ItemSavedDeviceBinding
import com.example.tymap.utils.PrefsHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SavedDevicesDialog(
    private val context: Context,
    private val onDeviceSelected: ((mac: String, name: String) -> Unit)? = null,
    private val onHistoryChanged: (() -> Unit)? = null
) {
    data class SavedDevice(
        val name: String,
        val mac: String,
        val rawEntry: String,
        val isLastConnected: Boolean
    )

    private var dialog: AlertDialog? = null
    private lateinit var binding: DialogManageSavedDevicesBinding
    private val deviceList = mutableListOf<SavedDevice>()
    private lateinit var adapter: SavedDeviceAdapter

    fun show() {
        binding = DialogManageSavedDevicesBinding.inflate(LayoutInflater.from(context))
        loadDevices()

        adapter = SavedDeviceAdapter(
            items = deviceList,
            onItemClick = { device ->
                dialog?.dismiss()
                onDeviceSelected?.invoke(device.mac, device.name)
            },
            onDeleteClick = { device ->
                confirmDeleteDevice(device)
            }
        )

        binding.rvSavedDevices.layoutManager = LinearLayoutManager(context)
        binding.rvSavedDevices.adapter = adapter

        updateUIState()

        binding.btnClearAllDevices.setOnClickListener {
            if (deviceList.isEmpty()) {
                Toast.makeText(context, "Danh sách thiết bị đang trống", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmClearAll()
        }

        binding.btnCloseDialog.setOnClickListener {
            dialog?.dismiss()
        }

        dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .create()

        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog?.show()
    }

    private fun loadDevices() {
        deviceList.clear()
        val historySet = PrefsHelper.getPairedHistory(context)
        val lastMac = PrefsHelper.getString(context, "last_device_mac", "").trim()

        val parsed = historySet.map { entry ->
            val mac = entry.substringAfter("(").substringBefore(")").trim().uppercase()
            val rawName = entry.substringBefore("(").trim()
            val name = if (rawName.isBlank() || rawName == "Thiết bị") "TYMAP" else rawName
            val isLast = lastMac.isNotEmpty() && (mac.equals(lastMac, ignoreCase = true) || entry.contains(lastMac, ignoreCase = true))
            SavedDevice(name = name, mac = mac, rawEntry = entry, isLastConnected = isLast)
        }.sortedWith(compareByDescending<SavedDevice> { it.isLastConnected }.thenBy { it.name })

        deviceList.addAll(parsed)
    }

    private fun updateUIState() {
        val count = deviceList.size
        binding.tvDeviceCountBadge.text = "$count thiết bị"
        if (count == 0) {
            binding.rvSavedDevices.visibility = View.GONE
            binding.tvEmptySavedDevices.visibility = View.VISIBLE
            binding.btnClearAllDevices.isEnabled = false
            binding.btnClearAllDevices.alpha = 0.5f
        } else {
            binding.rvSavedDevices.visibility = View.VISIBLE
            binding.tvEmptySavedDevices.visibility = View.GONE
            binding.btnClearAllDevices.isEnabled = true
            binding.btnClearAllDevices.alpha = 1.0f
        }
    }

    private fun confirmDeleteDevice(device: SavedDevice) {
        MaterialAlertDialogBuilder(context)
            .setTitle("Xác nhận xóa thiết bị")
            .setMessage("Bạn có chắc muốn xóa \"${device.name}\" (${device.mac}) khỏi danh sách đã lưu?")
            .setPositiveButton("Xóa") { _, _ ->
                deleteDevice(device)
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun deleteDevice(device: SavedDevice) {
        PrefsHelper.removePairedDevice(context, device.mac)
        val index = deviceList.indexOfFirst { it.mac.equals(device.mac, ignoreCase = true) }
        if (index != -1) {
            deviceList.removeAt(index)
            adapter.notifyItemRemoved(index)
            updateUIState()
        }
        onHistoryChanged?.invoke()
        Toast.makeText(context, "Đã xóa: ${device.name}", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(context)
            .setTitle("Xóa toàn bộ lịch sử")
            .setMessage("Bạn có chắc muốn xóa toàn bộ ${deviceList.size} thiết bị đã lưu?")
            .setPositiveButton("Xóa tất cả") { _, _ ->
                clearAllDevices()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun clearAllDevices() {
        PrefsHelper.clearPairedHistory(context)
        val oldSize = deviceList.size
        deviceList.clear()
        adapter.notifyItemRangeRemoved(0, oldSize)
        updateUIState()
        onHistoryChanged?.invoke()
        Toast.makeText(context, "Đã xóa toàn bộ lịch sử thiết bị", Toast.LENGTH_SHORT).show()
    }

    private class SavedDeviceAdapter(
        private val items: List<SavedDevice>,
        private val onItemClick: (SavedDevice) -> Unit,
        private val onDeleteClick: (SavedDevice) -> Unit
    ) : RecyclerView.Adapter<SavedDeviceAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemSavedDeviceBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemSavedDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.binding.tvDeviceName.text = item.name
            holder.binding.tvDeviceAddress.text = item.mac
            holder.binding.tvLastConnectedBadge.visibility = if (item.isLastConnected) View.VISIBLE else View.GONE

            holder.binding.root.setOnClickListener {
                onItemClick(item)
            }

            holder.binding.btnDeleteDevice.setOnClickListener {
                onDeleteClick(item)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
