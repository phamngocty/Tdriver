package com.example.tymap.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tymap.databinding.ActivityOledColorFilterBinding
import com.example.tymap.model.OledFilter
import com.example.tymap.utils.PrefsHelper
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class OledColorFilterActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOledColorFilterBinding
    private val filters = mutableListOf<OledFilter>()
    private lateinit var adapter: OledFilterAdapter
    private var selectedColor: Int = Color.BLACK
    private val gson = Gson()
    private var sampleBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOledColorFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadFilters()
        setupUI()
        loadSampleImage()
    }

    private fun loadSampleImage() {
        val file = java.io.File(filesDir, "sample_crop.png")
        if (file.exists()) {
            sampleBitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
            sampleBitmap?.let {
                binding.ivSampleImage.setImageBitmap(it)
            }
        }
    }

    private fun setupUI() {
        adapter = OledFilterAdapter(filters) { saveFilters() }
        binding.rvFilters.layoutManager = LinearLayoutManager(this)
        binding.rvFilters.adapter = adapter

        binding.ivSampleImage.setOnTouchListener { v, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                sampleBitmap?.let { bitmap ->
                    // Map touch coordinates to bitmap coordinates
                    val x = (event.x * bitmap.width / v.width).toInt().coerceIn(0, bitmap.width - 1)
                    val y = (event.y * bitmap.height / v.height).toInt().coerceIn(0, bitmap.height - 1)
                    
                    selectedColor = bitmap.getPixel(x, y)
                    updateCurrentColor()
                    
                    // Move pointer
                    binding.vPickerPointer.visibility = View.VISIBLE
                    binding.vPickerPointer.x = event.x + v.left - (binding.vPickerPointer.width / 2)
                    binding.vPickerPointer.y = event.y + v.top - (binding.vPickerPointer.height / 2)
                }
            }
            true
        }

        binding.sliderTolerance.addOnChangeListener { _, value, _ ->
            binding.tvToleranceValue.text = value.toInt().toString()
        }

        binding.sliderDither.addOnChangeListener { _, value, _ ->
            binding.tvDitherValue.text = value.toInt().toString()
        }

        binding.btnAddFilter.setOnClickListener {
            val filter = OledFilter(
                color = selectedColor,
                tolerance = binding.sliderTolerance.value.toInt(),
                dither = binding.sliderDither.value.toInt()
            )
            filters.add(0, filter)
            adapter.notifyItemInserted(0)
            binding.rvFilters.scrollToPosition(0)
            saveFilters()
        }
    }

    private fun updateCurrentColor() {
        binding.vCurrentColor.setBackgroundColor(selectedColor)
        binding.tvCurrentColorHex.text = String.format("#%06X", (0xFFFFFF and selectedColor))
    }

    private fun loadFilters() {
        val json = PrefsHelper.getColorFilters(this)
        val type = object : TypeToken<List<OledFilter>>() {}.type
        val list = gson.fromJson<List<OledFilter>>(json, type) ?: emptyList()
        filters.clear()
        filters.addAll(list)
    }

    private fun saveFilters() {
        val json = gson.toJson(filters)
        PrefsHelper.putColorFilters(this, json)
    }
}
