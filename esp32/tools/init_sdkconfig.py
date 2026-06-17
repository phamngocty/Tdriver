"""
PlatformIO extra script: Thêm include path cho sdkconfig.h
Framework cung cấp sẵn sdkconfig.h cho từng cấu hình flash/RAM.
Build system không tự động thêm path này, cần thêm thủ công.
"""
import os
from SCons.Script import DefaultEnvironment

env = DefaultEnvironment()
board_config = env.BoardConfig()

# Xác định variant dựa trên flash_mode và memory_type
flash_mode = board_config.get("build.flash_mode", "qio")
memory_type = board_config.get("build.arduino.memory_type", "qio_ram")

# Map memory_type → sdkconfig subdir (thường memory_type = qio_qspi, opi_opi, etc.)
variant_dir = memory_type

# Lấy path tới framework package
FRAMEWORK_DIR = env.PioPlatform().get_package_dir("framework-arduinoespressif32")
sdkconfig_inc = os.path.join(
    FRAMEWORK_DIR, "tools", "sdk", "esp32s3", variant_dir, "include"
)

if os.path.isdir(sdkconfig_inc):
    env.Append(CPPPATH=[sdkconfig_inc])
    print(f"[SDKCONFIG] Added include path: {sdkconfig_inc}")
else:
    print(f"[SDKCONFIG] Warning: {sdkconfig_inc} not found")
