package com.example.tymap.ui

import androidx.annotation.DrawableRes
import com.example.tymap.R

/**
 * Map maneuver index (từ mapManeuverToIcon() trong RoutingEngine) sang drawable resource.
 *
 * Bảng index:
 *  0 = straight/continue    4 = slight-left    8 = uturn-right
 *  1 = slight-right         5 = left            9+ = roundabout/arrive/merge...
 *  2 = right                6 = sharp-left
 *  3 = sharp-right          7 = uturn-left
 */
@DrawableRes
fun maneuverIconRes(iconIndex: Int): Int = when (iconIndex) {
    1    -> R.drawable.ic_nav_slight_right
    2    -> R.drawable.ic_nav_turn_right
    3    -> R.drawable.ic_nav_sharp_right
    4    -> R.drawable.ic_nav_slight_left
    5    -> R.drawable.ic_nav_turn_left
    6    -> R.drawable.ic_nav_sharp_left
    7, 8 -> R.drawable.ic_nav_uturn
    11, 12, 13 -> R.drawable.ic_nav_roundabout
    14   -> R.drawable.ic_nav_arrive
    else -> R.drawable.ic_nav_straight  // 0 = straight, unknown
}
