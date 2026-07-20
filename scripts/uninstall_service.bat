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
sc query "%SERVICE_NAME%" >nul 2>&1
if errorlevel 1 (
    echo Служба FinanceBot не установлена.
    pause
    exit /b 0
)
sc stop "%SERVICE_NAME%" >nul 2>&1
if exist "%NSSM%" (
    "%NSSM%" remove "%SERVICE_NAME%" confirm
) else (
    sc delete "%SERVICE_NAME%"
)
if errorlevel 1 (
    echo Не удалось удалить службу FinanceBot.
    pause
    exit /b 1
)
echo Служба FinanceBot удалена. Данные проекта не изменены.
pause
