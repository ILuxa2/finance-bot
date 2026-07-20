@echo off
chcp 65001 > nul
setlocal

fltmc >nul 2>&1
if errorlevel 1 (
    echo Запрашиваются права администратора...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

set "SERVICE_NAME=FinanceBot"
for %%I in ("%~dp0..") do set "PROJECT_ROOT=%%~fI"
set "NSSM=%PROJECT_ROOT%\tools\nssm\nssm.exe"
set "LOG_DIR=%PROJECT_ROOT%\logs"

if not exist "%NSSM%" (
    echo NSSM не найден.
    echo Скачайте NSSM и положите nssm.exe в tools/nssm/nssm.exe
    pause
    exit /b 1
)

sc query "%SERVICE_NAME%" >nul 2>&1
if not errorlevel 1 (
    echo Служба FinanceBot уже установлена.
    pause
    exit /b 0
)

if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

"%NSSM%" install "%SERVICE_NAME%" python
if errorlevel 1 goto :install_error
"%NSSM%" set "%SERVICE_NAME%" AppDirectory "%PROJECT_ROOT%"
"%NSSM%" set "%SERVICE_NAME%" AppParameters "-m app.main"
"%NSSM%" set "%SERVICE_NAME%" Start SERVICE_AUTO_START
"%NSSM%" set "%SERVICE_NAME%" AppStdout "%LOG_DIR%\stdout.log"
"%NSSM%" set "%SERVICE_NAME%" AppStderr "%LOG_DIR%\stderr.log"
"%NSSM%" set "%SERVICE_NAME%" AppExit Default Restart
"%NSSM%" set "%SERVICE_NAME%" AppRestartDelay 5000
"%NSSM%" set "%SERVICE_NAME%" AppThrottle 1500

"%NSSM%" start "%SERVICE_NAME%"
if errorlevel 1 (
    echo Служба установлена, но не запустилась. Проверьте logs\stderr.log.
    pause
    exit /b 1
)
echo Служба FinanceBot установлена и запущена.
pause
exit /b 0

:install_error
echo Не удалось создать службу FinanceBot.
pause
exit /b 1
