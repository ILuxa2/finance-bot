@echo off
chcp 65001 > nul
cd /d "%~dp0"

echo ==============================
echo   Finance Bot запускается...
echo ==============================

python -m app.main

pause
