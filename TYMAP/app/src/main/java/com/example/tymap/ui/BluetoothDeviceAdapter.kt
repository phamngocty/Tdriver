package com.example.tymap.ui

import android.bluetooth.BluetoothDevice
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.R

data class DiscoveredBleDevice(
    val device: BluetoothDevice,
    val displayName: String
)

class BluetoothDeviceAdapter(private val onDeviceClick: (BluetoothDevice) -> Unit) :
    RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<DiscoveredBleDevice>()

    @SuppressLint("MissingPermission")
    fun addDevice(device: BluetoothDevice, scanRecordName: String? = null) {
        val mac = device.address.uppercase()
        val name = scanRecordName?.takeIf { it.isNotBlank() } ?: device.name?.takeIf { it.isNotBlank() } ?: "Thiết bị BLE"
        val existingIndex = devices.indexOfFirst { it.device.address.equals(mac, ignoreCase = true) }
        if (existingIndex < 0) {
            devices.add(DiscoveredBleDevice(device, name))
            notifyItemInserted(devices.size - 1)
        } else if (name != "Thiết bị BLE" && devices[existingIndex].displayName != name) {
            devices[existingIndex] = DiscoveredBleDevice(device, name)
            notifyItemChanged(existingIndex)
        }
    }

    fun clearDevices() {
        val count = devices.size
        devices.clear()
        notifyItemRangeRemoved(0, count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = devices[position]
        holder.tvName.text = item.displayName
        holder.tvAddress.text = item.device.address
        
        holder.itemView.setOnClickListener { 
            onDeviceClick(item.device) 
        }
    }

    override fun getItemCount(): Int = devices.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
    }
}
