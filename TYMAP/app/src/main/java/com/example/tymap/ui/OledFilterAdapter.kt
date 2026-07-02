package com.example.tymap.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.databinding.ItemOledFilterBinding
import com.example.tymap.model.OledFilter

class OledFilterAdapter(
    private val filters: MutableList<OledFilter>,
    private val onFilterChanged: () -> Unit
) : RecyclerView.Adapter<OledFilterAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemOledFilterBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemOledFilterBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val filter = filters[position]
        holder.binding.vFilterColor.setBackgroundColor(filter.color)
        holder.binding.tvFilterInfo.text = String.format("#%06X (±%d, D%d)", 
            (0xFFFFFF and filter.color), filter.tolerance, filter.dither)
        
        holder.binding.cbActive.isChecked = filter.isActive
        holder.binding.cbActive.setOnCheckedChangeListener { _, isChecked ->
            filter.isActive = isChecked
            onFilterChanged()
        }

        holder.binding.ivDelete.setOnClickListener {
            filters.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, filters.size)
            onFilterChanged()
        }
    }

    override fun getItemCount() = filters.size
}
