@echo off
chcp 65001 > nul
setlocal
fltmc >nul 2>&1
if errorlevel 1 (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)
sc stop FinanceBot >nul 2>&1
timeout /t 2 /nobreak >nul
sc start FinanceBot
pause
