package com.example.tymap.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.databinding.ItemRouteStepBinding
import com.example.tymap.repository.StepInfo
import java.util.Locale

class RouteStepsAdapter : RecyclerView.Adapter<RouteStepsAdapter.ViewHolder>() {

    var onStepClickListener: ((StepInfo) -> Unit)? = null
    private var steps = emptyList<StepInfo>()

    fun submitList(newSteps: List<StepInfo>) {
        steps = newSteps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRouteStepBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val step = steps[position]
        holder.bind(step, position == steps.size - 1)
        holder.itemView.setOnClickListener {
            onStepClickListener?.invoke(step)
        }
    }

    override fun getItemCount(): Int = steps.size

    class ViewHolder(private val binding: ItemRouteStepBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(step: StepInfo, isLast: Boolean) {
            binding.tvStepInstruction.text = step.instruction
            
            // Định dạng khoảng cách
            val dist = step.distance
            binding.tvStepDistance.text = if (dist >= 1000) {
                String.format(Locale.getDefault(), "%.1f km", dist / 1000.0)
            } else {
                "${dist.toInt()} m"
            }

            // Hiển thị chi tiết (tên đường hoặc ghi chú) nếu có
            if (step.roadName.isNotEmpty() && step.roadName != step.instruction) {
                binding.tvStepDetail.text = step.roadName
                binding.tvStepDetail.visibility = View.VISIBLE
            } else {
                binding.tvStepDetail.visibility = View.GONE
            }

            // Gán icon rẽ
            binding.ivStepIcon.setImageResource(maneuverIconRes(step.maneuverIcon))

            // Ẩn/Hiện đường kẻ thẳng đứng (nếu là bước cuối thì ẩn)
            binding.viewLine.visibility = if (isLast) View.INVISIBLE else View.VISIBLE
        }
    }
}
