package com.example.tymap.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

import android.widget.ImageView
import androidx.core.content.ContextCompat
import com.example.tymap.R

class SuggestionAdapter(private val onItemSelected: (JSONObject) -> Unit) :
    RecyclerView.Adapter<SuggestionAdapter.ViewHolder>() {

    private var items = emptyList<JSONObject>()

    fun submitList(newList: List<JSONObject>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_suggestion, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val displayName = item.optString("display_name", "")
        val provider = item.optString("provider", "")
        val isCurrentLocation = item.optBoolean("is_current_location", false)
        
        val parts = displayName.split(",")
        val title = parts.getOrNull(0)?.trim() ?: ""
        val subtitle = parts.drop(1).joinToString(",").trim()
        
        holder.text1.text = title
        holder.text2.text = if (provider.isNotEmpty()) {
            if (subtitle.isNotEmpty()) "$subtitle • [$provider]" else "[$provider]"
        } else {
            subtitle
        }
        
        holder.icon.setImageResource(if (isCurrentLocation) R.drawable.ic_my_location else R.drawable.ic_map)
        holder.icon.imageTintList = ContextCompat.getColorStateList(holder.itemView.context, 
            if (isCurrentLocation) R.color.blue_primary else android.R.color.darker_gray)

        holder.itemView.setOnClickListener { onItemSelected(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(R.id.tvTitle)
        val text2: TextView = view.findViewById(R.id.tvSubtitle)
        val icon: ImageView = view.findViewById(R.id.ivIcon)
    }
}
