package com.example.tymap.ui

import android.bluetooth.BluetoothDevice
import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

import com.example.tymap.R

class BluetoothDeviceAdapter(private val onDeviceClick: (BluetoothDevice) -> Unit) :
    RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<BluetoothDevice>()

    @SuppressLint("MissingPermission")
    fun addDevice(device: BluetoothDevice) {
        if (!devices.any { it.address == device.address }) {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_bluetooth_device, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        holder.tvName.text = device.name ?: "Unknown Device"
        holder.tvAddress.text = device.address
        
        holder.itemView.setOnClickListener { 
            onDeviceClick(device) 
        }
    }

    override fun getItemCount(): Int = devices.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvAddress: TextView = view.findViewById(R.id.tvDeviceAddress)
    }
}
