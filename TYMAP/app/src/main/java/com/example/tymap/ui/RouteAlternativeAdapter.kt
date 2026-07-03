package com.example.tymap.ui

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
        
        holder.itemView.setBackgroundColor(if (route.isSelected) 0x20007AFF else 0x00000000)
        
        holder.itemView.setOnClickListener { onRouteSelected(position) }
    }

    override fun getItemCount(): Int = routes.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text1: TextView = view.findViewById(android.R.id.text1)
    }
}
