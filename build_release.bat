@echo off
chcp 65001 >nul
echo ===================================================================
echo       TYMAP & ESP32 AUTO BUILD & RELEASE PIPELINE
echo ===================================================================
echo.

set ROOT_DIR=%~dp0
set DIST_DIR=%ROOT_DIR%release_dist

if not exist "%DIST_DIR%" (
    mkdir "%DIST_DIR%"
)

echo [1/3] Đang biên dịch Android APK...
cd /d "%ROOT_DIR%TYMAP"
call gradlew.bat assembleDebug
if %errorlevel% neq 0 (
    echo [ERROR] Biên dịch Android APK thất bại!
    pause
    exit /b %errorlevel%
)

echo.
echo [2/3] Đang biên dịch ESP32-S3 Firmware...
cd /d "%ROOT_DIR%TYMAP\firmware\esp32_s3_gc9a01"
call "%USERPROFILE%\.platformio\penv\Scripts\platformio.exe" run
if %errorlevel% neq 0 (
    echo [ERROR] Biên dịch ESP32 Firmware thất bại!
    pause
    exit /b %errorlevel%
)

echo.
echo [3/3] Đang xuất bản file ra thư mục: release_dist
copy /Y "%ROOT_DIR%TYMAP\app\build\outputs\apk\debug\app-debug.apk" "%DIST_DIR%\TYMAP_Latest.apk"
copy /Y "%ROOT_DIR%TYMAP\firmware\esp32_s3_gc9a01\.pio\build\esp32-s3-devkitc-1\firmware.bin" "%DIST_DIR%\firmware_Latest.bin"

echo.
echo ===================================================================
echo  ✅ HOÀN TẤT XUẤT BẢN THÀNH CÔNG!
echo ===================================================================
echo  Các file đã được gom vào thư mục: %DIST_DIR%
echo    1. TYMAP_Latest.apk
echo    2. firmware_Latest.bin
echo.
echo  Bước tiếp theo:
echo    - Tải 2 file trên lên Gitea/GSM attachment hoặc server của bạn.
echo    - Cập nhật link tải trong version.json (ở thư mục gốc).
echo ===================================================================
echo.
pause
