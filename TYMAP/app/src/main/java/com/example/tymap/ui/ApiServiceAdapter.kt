package com.example.tymap.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.RecyclerView
import com.example.tymap.R
import com.example.tymap.databinding.ItemApiServiceBinding

data class ApiService(
    val id: String,
    val name: String,
    val description: String,
    val registrationUrl: String,
    val isKeyRequired: Boolean,
    var apiKey: String = "",
    var status: ServiceStatus = ServiceStatus.NOT_CONFIGURED,
    var isExpanded: Boolean = false
)

enum class ServiceStatus {
    CONFIGURED, NOT_CONFIGURED, FREE, TESTING, ERROR
}

class ApiServiceAdapter(
    private val services: List<ApiService>,
    private val onTestClick: (ApiService) -> Unit,
    private val onRegisterClick: (ApiService) -> Unit,
    private val onKeyChanged: (ApiService, String) -> Unit
) : RecyclerView.Adapter<ApiServiceAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemApiServiceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val service = services[position]
        holder.bind(service)
    }

    override fun getItemCount(): Int = services.size

    inner class ViewHolder(private val binding: ItemApiServiceBinding) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(service: ApiService) {
            binding.tvServiceName.text = service.name
            binding.tvServiceDescription.text = service.description
            
            // Expansion logic
            binding.layoutDetails.visibility = if (service.isExpanded) View.VISIBLE else View.GONE
            binding.ivExpandArrow.rotation = if (service.isExpanded) 180f else 0f
            
            binding.layoutHeader.setOnClickListener {
                service.isExpanded = !service.isExpanded
                notifyItemChanged(bindingAdapterPosition)
            }
            
            // API Key field
            if (service.isKeyRequired) {
                binding.tilApiKey.visibility = View.VISIBLE
                binding.etApiKey.setText(service.apiKey)
                binding.etApiKey.addTextChangedListener {
                    val newKey = it?.toString() ?: ""
                    if (service.apiKey != newKey) {
                        service.apiKey = newKey // Update local object
                        onKeyChanged(service, newKey) // Trigger save
                    }
                }
                binding.btnRegisterService.visibility = View.VISIBLE
            } else {
                binding.tilApiKey.visibility = View.GONE
                binding.btnRegisterService.visibility = View.GONE
            }
            
            // Status Icon and Text
            val (statusColor, statusText) = when (service.status) {
                ServiceStatus.CONFIGURED -> android.R.color.holo_green_dark to "Đã kết nối"
                ServiceStatus.FREE -> android.R.color.holo_blue_dark to "Miễn phí"
                ServiceStatus.ERROR -> android.R.color.holo_red_dark to "Lỗi cấu hình"
                ServiceStatus.TESTING -> R.color.status_testing to "Đang kiểm tra..."
                ServiceStatus.NOT_CONFIGURED -> android.R.color.darker_gray to "Chưa cấu hình"
            }
            binding.ivServiceStatus.imageTintList = ContextCompat.getColorStateList(itemView.context, statusColor)
            binding.tvServiceStatusText.text = statusText
            binding.tvServiceStatusText.setTextColor(ContextCompat.getColor(itemView.context, statusColor))
            
            // Buttons
            binding.btnTestService.setOnClickListener { onTestClick(service) }
            binding.btnRegisterService.setOnClickListener { onRegisterClick(service) }
        }
    }
}
