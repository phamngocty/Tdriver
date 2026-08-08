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
        holder.text1.text = "${route.engineName}: $time min ($dist)"
        holder.text1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)

        val context = holder.itemView.context
        val density = context.resources.displayMetrics.density
        val paddingH = (16 * density).toInt()
        val paddingV = (12 * density).toInt()
        holder.itemView.setPadding(paddingH, paddingV, paddingH, paddingV)

        if (route.isSelected) {
            val selectedDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#334B68"))
                cornerRadius = 8 * density
            }
            holder.itemView.background = selectedDrawable
            holder.text1.setTextColor(Color.WHITE)
            holder.text1.setTypeface(null, Typeface.BOLD)
        } else {
            holder.itemView.background = null
            holder.text1.setTextColor(Color.parseColor("#9CA3AF"))
            holder.text1.setTypeface(null, Typeface.NORMAL)
        }

        holder.itemView.setOnClickListener { onRouteSelected(position) }
    }

    override fun getItemCount(): Int = routes.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(android.R.id.text1)
    }
}

