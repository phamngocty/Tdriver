package com.example.tymap.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
    override fun getItemCount(): Int = 5

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ConnectionFragment()
            1 -> MapFragment()
            2 -> SettingsFragment()
            3 -> NotificationsFragment()
            4 -> RenderFragment()
            else -> throw IllegalArgumentException("Invalid position")
        }
    }
}
