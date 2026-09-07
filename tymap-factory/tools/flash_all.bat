@echo off
chcp 65001 > nul
title TYMAP Flash All Dual-Boot
echo ========================================================
echo        TYMAP ESP32-C3 FLASH ALL DUAL-BOOT
echo   Nap ca 2 He Dieu Hanh: Android (TYMAP) ^& iOS (Sygic)
echo ========================================================
cd /d "%~dp0"
"C:\Users\phamn\.platformio\penv\Scripts\python.exe" flasher.py all
pause
