package com.example.tymap.model

data class OledFilter(
    val color: Int, // Hex Color
    val tolerance: Int, // Độ nhạy 0-100
    val dither: Int, // Độ dither 0-100
    var isActive: Boolean = true
)
