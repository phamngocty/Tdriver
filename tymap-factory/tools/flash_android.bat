@echo off
chcp 65001 > nul
title TYMAP Flash Android (ota_0)
echo ========================================================
echo        NAP FIRMWARE ANDROID (TYMAP BLE) VAO ota_0
echo ========================================================
cd /d "%~dp0"
"C:\Users\phamn\.platformio\penv\Scripts\python.exe" flasher.py android
pause
