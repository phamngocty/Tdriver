import re

with open('TYMAP/app/src/main/java/com/example/tymap/ui/SettingsFragment.kt', 'r', encoding='utf-8') as f:
    code = f.read()

# 1. Update setupUI() to include switchManualShowOled
old_setup_ui = """    private fun setupUI() {
        val context = requireContext()"""

new_setup_ui = """    private fun setupUI() {
        val context = requireContext()
        
        binding.switchManualShowOled.isChecked = PrefsHelper.getBoolean(context, "manual_show_oled", false)
        binding.switchManualShowOled.setOnCheckedChangeListener { _, isChecked ->
            PrefsHelper.putBoolean(context, "manual_show_oled", isChecked)
            updateOledSettingsVisibility()
        }
        updateOledSettingsVisibility()
"""

code = code.replace(old_setup_ui, new_setup_ui)

# 2. Add updateOledSettingsVisibility() method
new_method = """
    private fun updateOledSettingsVisibility(isOledConnected: Boolean = false) {
        if (_binding == null) return
        val manualShow = PrefsHelper.getBoolean(requireContext(), "manual_show_oled", false)
        val shouldShow = isOledConnected || manualShow
        binding.layoutOledSettings.visibility = if (shouldShow) View.VISIBLE else View.GONE
    }
"""
# Insert before observeDeviceType
old_observe = """    private fun observeDeviceType() {"""
code = code.replace(old_observe, new_method + "\n" + old_observe)

# 3. Update observeDeviceType()
old_observe_body = """    private fun observeDeviceType() {
        viewLifecycleOwner.lifecycleScope.launch {
            NavigationRepository.deviceStatus.collect { status ->
                val display = status["display"] ?: ""
                val isOled = display.contains("OLED") || display.contains("SSD1306")
                
                if (isOled && _binding != null) {
                    val currentMode = PrefsHelper.getInt(requireContext(), "map_capture_mode", 0)
                    if (currentMode == 3) {
                        binding.spinnerMapCaptureMode.setSelection(0)
                        PrefsHelper.putInt(requireContext(), "map_capture_mode", 0)
                        PrefsHelper.putBoolean(requireContext(), "tile_streaming", false)
                        updateCropVisibility(0)
                        Toast.makeText(requireContext(), "Thiết bị OLED không hỗ trợ Tile Streaming. Chuyển về bản đồ tĩnh.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }"""

new_observe_body = """    private fun observeDeviceType() {
        viewLifecycleOwner.lifecycleScope.launch {
            NavigationRepository.deviceStatus.collect { status ->
                val display = status["display"] ?: ""
                val isOled = display.contains("OLED") || display.contains("SSD1306")
                
                updateOledSettingsVisibility(isOled)
                
                if (isOled && _binding != null) {
                    val currentMode = PrefsHelper.getInt(requireContext(), "map_capture_mode", 0)
                    if (currentMode == 3) {
                        binding.spinnerMapCaptureMode.setSelection(0)
                        PrefsHelper.putInt(requireContext(), "map_capture_mode", 0)
                        PrefsHelper.putBoolean(requireContext(), "tile_streaming", false)
                        updateCropVisibility(0)
                        Toast.makeText(requireContext(), "Thiết bị OLED không hỗ trợ Tile Streaming. Chuyển về bản đồ tĩnh.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }"""

code = code.replace(old_observe_body, new_observe_body)

# 4. Also update observeBleState to hide OLED if disconnected and manualShow is false
old_ble_state = """    private fun updateConnectionStatusUI(state: NavigationRepository.BleConnectionState) {
        if (_binding == null) return
        when (state) {"""

new_ble_state = """    private fun updateConnectionStatusUI(state: NavigationRepository.BleConnectionState) {
        if (_binding == null) return
        if (state == NavigationRepository.BleConnectionState.Disconnected) {
            updateOledSettingsVisibility(false)
        }
        when (state) {"""

code = code.replace(old_ble_state, new_ble_state)


with open('TYMAP/app/src/main/java/com/example/tymap/ui/SettingsFragment.kt', 'w', encoding='utf-8') as f:
    f.write(code)
print("Updated SettingsFragment.kt")
