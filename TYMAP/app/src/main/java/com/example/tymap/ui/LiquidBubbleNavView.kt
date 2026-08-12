package com.example.tymap.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.tymap.R
import com.example.tymap.databinding.LayoutLiquidBubbleNavBinding

class LiquidBubbleNavView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val binding: LayoutLiquidBubbleNavBinding =
        LayoutLiquidBubbleNavBinding.inflate(LayoutInflater.from(context), this, true)

    private var selectedIndex = 0
    private var onItemSelectedListener: ((Int) -> Unit)? = null

    private val tabs: List<View> by lazy {
        listOf(binding.tabMap, binding.tabSettings, binding.tabNotifications, binding.tabRender)
    }

    private val bubbles: List<FrameLayout> by lazy {
        listOf(binding.bubbleMap, binding.bubbleSettings, binding.bubbleNotifications, binding.bubbleRender)
    }

    private val icons: List<ImageView> by lazy {
        listOf(binding.iconMap, binding.iconSettings, binding.iconNotifications, binding.iconRender)
    }

    private val labels: List<TextView> by lazy {
        listOf(binding.labelMap, binding.labelSettings, binding.labelNotifications, binding.labelRender)
    }

    init {
        clipChildren = false
        clipToPadding = false

        tabs.forEachIndexed { index, tabView ->
            tabView.setOnClickListener {
                if (selectedIndex != index) {
                    setSelectedTab(index, animate = true)
                    onItemSelectedListener?.invoke(index)
                }
            }
        }

        // Initial setup
        post {
            setSelectedTab(0, animate = false)
        }
    }

    fun setOnItemSelectedListener(listener: (Int) -> Unit) {
        this.onItemSelectedListener = listener
    }

    fun setSelectedTab(position: Int, animate: Boolean = true) {
        if (position < 0 || position >= tabs.size) return
        selectedIndex = position

        tabs.forEachIndexed { index, _ ->
            val bubble = bubbles[index]
            val icon = icons[index]
            val label = labels[index]
            val isSelected = index == position

            val targetTranslationY = if (isSelected) -dpToPx(14f) else 0f
            val targetScale = if (isSelected) 1.12f else 1.0f

            if (isSelected) {
                bubble.setBackgroundResource(R.drawable.bg_liquid_active_bubble)
                icon.setColorFilter(ContextCompat.getColor(context, R.color.white))
                label.setTextColor(ContextCompat.getColor(context, R.color.colorAccentCyan))
            } else {
                bubble.background = null
                icon.setColorFilter(ContextCompat.getColor(context, R.color.colorTextMuted))
                label.setTextColor(ContextCompat.getColor(context, R.color.colorTextMuted))
            }

            if (animate) {
                bubble.animate()
                    .translationY(targetTranslationY)
                    .scaleX(targetScale)
                    .scaleY(targetScale)
                    .setDuration(280)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            } else {
                bubble.translationY = targetTranslationY
                bubble.scaleX = targetScale
                bubble.scaleY = targetScale
            }
        }
    }

    fun setRenderTabVisible(visible: Boolean) {
        binding.tabRender.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun dpToPx(dp: Float): Float {
        return dp * context.resources.displayMetrics.density
    }
}
