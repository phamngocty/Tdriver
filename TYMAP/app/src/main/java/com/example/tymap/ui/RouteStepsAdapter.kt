package com.example.tymap.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.databinding.ItemRouteStepBinding
import com.example.tymap.repository.StepInfo
import com.example.tymap.service.PointWeather
import java.util.Locale

class RouteStepsAdapter : RecyclerView.Adapter<RouteStepsAdapter.ViewHolder>() {

    var onStepClickListener: ((StepInfo) -> Unit)? = null
    var destinationWeather: PointWeather? = null
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

    inner class ViewHolder(private val binding: ItemRouteStepBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(step: StepInfo, isLast: Boolean) {
            binding.tvStepInstruction.text = step.instruction
            
            // Định dạng khoảng cách
            val dist = step.distance
            binding.tvStepDistance.text = if (dist >= 1000) {
                String.format(Locale.getDefault(), "%.1f km", dist / 1000.0)
            } else {
                "${dist.toInt()} m"
            }

            // Hiển thị chi tiết (thời tiết điểm đến hoặc tên đường) nếu có
            if (isLast && destinationWeather != null) {
                val w = destinationWeather!!
                binding.tvStepDetail.text = "🏁 Dự báo khi đến (${w.timeLabel}): ${w.icon} ${w.tempC}°C • ${w.status}"
                binding.tvStepDetail.setTextColor(if (w.isRainAlert) Color.parseColor("#F87171") else Color.parseColor("#38BDF8"))
                binding.tvStepDetail.visibility = View.VISIBLE
            } else if (step.roadName.isNotEmpty() && step.roadName != step.instruction) {
                binding.tvStepDetail.text = step.roadName
                binding.tvStepDetail.setTextColor(Color.parseColor("#388E3C"))
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
