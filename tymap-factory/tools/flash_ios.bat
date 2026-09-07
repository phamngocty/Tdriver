@echo off
chcp 65001 > nul
title TYMAP Flash iOS Sygic (ota_1)
echo ========================================================
echo        NAP FIRMWARE IOS (SYGIC BLE) VAO ota_1
echo ========================================================
cd /d "%~dp0"
"C:\Users\phamn\.platformio\penv\Scripts\python.exe" flasher.py ios
pause
