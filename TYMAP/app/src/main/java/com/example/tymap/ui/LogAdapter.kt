package com.example.tymap.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(private var logs: MutableList<String>) : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLog: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.tvLog.text = logs[position]
        holder.tvLog.textSize = 10f
        holder.tvLog.setPadding(16, 4, 16, 4)
    }

    override fun getItemCount(): Int = logs.size

    fun updateLogs(newLogs: List<String>) {
        this.logs = newLogs.toMutableList()
        notifyDataSetChanged()
    }
}
