package com.example.tymap.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.R

data class NotificationApp(
    val packageName: String,
    val name: String,
    val icon: android.graphics.drawable.Drawable?,
    var isEnabled: Boolean
)

enum class AdapterMode {
    MANAGE, // Show Delete button
    SELECT  // Show Checkbox
}

class NotificationAppAdapter(
    private var apps: List<NotificationApp>,
    private val mode: AdapterMode = AdapterMode.MANAGE,
    private val onAction: (String, Boolean) -> Unit
) : RecyclerView.Adapter<NotificationAppAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivIcon: ImageView = view.findViewById(R.id.ivAppIcon)
        val tvName: TextView = view.findViewById(R.id.tvAppName)
        val ivDelete: ImageView = view.findViewById(R.id.ivDeleteApp)
        val cbSelect: CheckBox = view.findViewById(R.id.cbAppSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_notification_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        holder.tvName.text = app.name
        holder.ivIcon.setImageDrawable(app.icon)
        
        when (mode) {
            AdapterMode.MANAGE -> {
                holder.ivDelete.visibility = View.VISIBLE
                holder.cbSelect.visibility = View.GONE
                holder.ivDelete.setOnClickListener {
                    onAction(app.packageName, false)
                }
            }
            AdapterMode.SELECT -> {
                holder.ivDelete.visibility = View.GONE
                holder.cbSelect.visibility = View.VISIBLE
                
                // Clear listener before setting checked state to avoid recursion if any
                holder.cbSelect.setOnCheckedChangeListener(null)
                holder.cbSelect.isChecked = app.isEnabled
                
                holder.cbSelect.setOnCheckedChangeListener { _, isChecked ->
                    app.isEnabled = isChecked
                    onAction(app.packageName, isChecked)
                }
            }
        }
    }

    override fun getItemCount() = apps.size

    fun updateList(newApps: List<NotificationApp>) {
        apps = newApps
        notifyDataSetChanged()
    }
}
