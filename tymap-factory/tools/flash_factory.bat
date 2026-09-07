@echo off
chcp 65001 > nul
title TYMAP Flash Factory Portal
echo ========================================================
echo        NAP WEB PORTAL VAO PHAN VUNG FACTORY
echo ========================================================
cd /d "%~dp0"
"C:\Users\phamn\.platformio\penv\Scripts\python.exe" flasher.py factory
pause
