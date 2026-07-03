package com.example.tymap.ble

import java.util.UUID

object BleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("0000feed-0000-1000-8000-00805f9b34fb") // Placeholder service UUID
    val CHA_NAV: UUID = UUID.fromString("0b11deef-1563-447f-aece-d3dfeb1c1f20")
    val CHA_NAV_TBT_ICON: UUID = UUID.fromString("d4d8fcca-16b2-4b8e-8ed5-90137c44a8ad")
    val CHA_GPS_SPEED: UUID = UUID.fromString("98b6073a-5cf3-4e73-b6d3-f8e05fa018a9")
    val CHA_SETTINGS: UUID = UUID.fromString("9d37a346-63d3-4df6-8eee-f0242949f59f")
    val CHA_TIME: UUID = UUID.fromString("a1b2c3d4-e5f6-4789-a012-3456789abcde")
    val CHA_WEATHER: UUID = UUID.fromString("b2c3d4e5-f6a7-4890-b123-456789abcdef")
    val CHA_MAP_IMAGE: UUID = UUID.fromString("c3d4e5f6-a7b8-4901-c234-567890abcdef")
    val CHA_DEVICE_CTRL: UUID = UUID.fromString("d4e5f6a7-b8c9-4012-d345-678901bcdef0")
    val CHA_REMOTE_CMD: UUID = UUID.fromString("f1a2b3c4-d5e6-4789-a012-3456789abcde")
    val CHA_DEVICE_STATUS: UUID = UUID.fromString("a1b2c3d4-e5f6-4789-b012-3456789abcde")
    val CHA_ICON_DATA: UUID = UUID.fromString("e2f3a4b5-c6d7-4890-e123-456789abcdef")
    val CHA_OLED_IMAGE: UUID = UUID.fromString("e1f2a3b4-c5d6-4789-a012-3456789abcde")
    val CHA_NOTIFICATION: UUID = UUID.fromString("c1d2e3f4-a5b6-4789-c012-3456789abcde")
    val CHA_PHONE_BATTERY: UUID = UUID.fromString("e5f6a7b8-c9d0-4123-e456-789012cdef01")
    
    // Tile Streaming Characteristics
    val CHA_MAP_TILE: UUID = UUID.fromString("d1e2f3a4-b5c6-4789-d012-3456789abcde")
    val CHA_MAP_CTRL: UUID = UUID.fromString("e2f3a4b5-c6d7-4890-e123-456789abcdef")
    val CHA_MAP_STATUS: UUID = UUID.fromString("f3a4b5c6-d7e8-4901-f234-567890abcdef")
}
