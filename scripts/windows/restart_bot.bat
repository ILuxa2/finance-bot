@echo off
chcp 65001 > nul
setlocal

set "SCRIPT_DIR=%~dp0"
call "%SCRIPT_DIR%stop_bot.bat"
timeout /t 3 /nobreak > nul
call "%SCRIPT_DIR%start_bot.bat"

exit /b %ERRORLEVEL%
