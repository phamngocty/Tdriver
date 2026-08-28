package com.example.tymap.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.R
import org.json.JSONObject

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
        val isCurrentLocation = item.optBoolean("is_current_location", false)
        val isSavedPlace = item.optBoolean("is_saved_place", false)
        val isPinAction = item.optBoolean("is_pin_action", false)
        val category = item.optString("category", "")

        val parts = displayName.split(",")
        val title = item.optString("title", parts.getOrNull(0)?.trim() ?: "")
        val subtitle = item.optString("address", parts.drop(1).joinToString(",").trim())

        holder.text1.text = title
        holder.text2.text = subtitle
        holder.text2.visibility = if (subtitle.isNotEmpty()) View.VISIBLE else View.GONE

        val context = holder.itemView.context
        when {
            isPinAction -> {
                holder.icon.setImageResource(R.drawable.ic_red_pin)
                holder.icon.imageTintList = ContextCompat.getColorStateList(context, R.color.red_primary)
            }
            isCurrentLocation -> {
                holder.icon.setImageResource(R.drawable.ic_my_location)
                holder.icon.imageTintList = ContextCompat.getColorStateList(context, R.color.blue_primary)
            }
            isSavedPlace -> {
                when (category) {
                    "HOME" -> {
                        holder.icon.setImageResource(R.drawable.ic_home)
                        holder.icon.imageTintList = ContextCompat.getColorStateList(context, R.color.blue_primary)
                    }
                    "WORK" -> {
                        holder.icon.setImageResource(R.drawable.ic_map)
                        holder.icon.imageTintList = ContextCompat.getColorStateList(context, R.color.speed_amber)
                    }
                    "FAVORITE" -> {
                        holder.icon.setImageResource(R.drawable.ic_favorite)
                        holder.icon.imageTintList = ContextCompat.getColorStateList(context, R.color.red_primary)
                    }
                    else -> {
                        holder.icon.setImageResource(R.drawable.ic_history)
                        holder.icon.imageTintList = ContextCompat.getColorStateList(context, android.R.color.darker_gray)
                    }
                }
            }
            else -> {
                holder.icon.setImageResource(R.drawable.ic_map)
                holder.icon.imageTintList = ContextCompat.getColorStateList(context, android.R.color.darker_gray)
            }
        }

        holder.itemView.setOnClickListener { onItemSelected(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(R.id.tvTitle)
        val text2: TextView = view.findViewById(R.id.tvSubtitle)
        val icon: ImageView = view.findViewById(R.id.ivIcon)
    }
}
