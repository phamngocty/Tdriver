package com.example.tymap.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.repository.RouteInfo

class RouteAlternativeAdapter(private val onRouteSelected: (Int) -> Unit) :
    RecyclerView.Adapter<RouteAlternativeAdapter.ViewHolder>() {

    private var routes = emptyList<RouteInfo>()

    fun submitList(newRoutes: List<RouteInfo>) {
        routes = newRoutes
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val route = routes[position]
        val time = (route.duration / 60).toInt()
        val dist = String.format("%.1f km", route.distance / 1000)
        
        val isNas = route.engineName.contains("NAS") || route.engineName.contains("GraphHopper")
        val badge = if (isNas && !route.engineName.contains("[Server Nhà]")) " [Server Nhà]" else ""
        holder.text1.text = "${route.engineName}$badge: $time phút ($dist)"
        holder.text1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)

        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        val paddingH = (12 * density).toInt()
        val paddingV = (10 * density).toInt()
        holder.itemView.setPadding(paddingH, paddingV, paddingH, paddingV)

        if (route.isSelected) {
            val selectedDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#1E293B"))
                setStroke((1.5 * density).toInt(), Color.parseColor("#38BDF8"))
                cornerRadius = 12 * density
            }
            holder.itemView.background = selectedDrawable
            holder.text1.setTextColor(Color.parseColor("#38BDF8"))
            holder.text1.setTypeface(null, Typeface.BOLD)
        } else {
            val unselectedDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#0F172A"))
                setStroke((1 * density).toInt(), Color.parseColor("#33475569"))
                cornerRadius = 12 * density
            }
            holder.itemView.background = unselectedDrawable
            holder.text1.setTextColor(Color.parseColor("#94A3B8"))
            holder.text1.setTypeface(null, Typeface.NORMAL)
        }

        holder.itemView.setOnClickListener { onRouteSelected(position) }
    }

    override fun getItemCount(): Int = routes.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(android.R.id.text1)
    }
}

