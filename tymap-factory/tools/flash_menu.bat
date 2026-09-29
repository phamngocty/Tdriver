@echo off
chcp 65001 > nul
title TYMAP Flasher Menu - Chon Cong COM ^& Erase Flash
echo ========================================================
echo        TYMAP ESP32-C3 DUAL-BOOT FLASHER MENU
echo ========================================================
cd /d "%~dp0"

if exist "C:\Users\phamn\.platformio\penv\Scripts\python.exe" (
    "C:\Users\phamn\.platformio\penv\Scripts\python.exe" flasher.py
) else (
    python flasher.py
)

pause
