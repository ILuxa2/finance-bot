@echo off
chcp 65001 > nul
setlocal

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%..\.." > nul

if not exist "logs" mkdir "logs"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "try { $process = Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'python.exe' -or $_.Name -eq 'pythonw.exe') -and $_.CommandLine -like '*app.main*' } | Select-Object -First 1; if ($null -ne $process) { exit 1 }; exit 0 } catch { Write-Error $_; exit 2 }"
if "%ERRORLEVEL%"=="1" (
  echo FinanceOS уже запущен.
  echo [%date% %time%] FinanceOS already running.>>"logs\windows_stdout.log"
  popd > nul
  exit /b 0
)
if not "%ERRORLEVEL%"=="0" (
  set "EXIT_CODE=%ERRORLEVEL%"
  popd > nul
  exit /b %EXIT_CODE%
)

echo [%date% %time%] Starting FinanceOS Bot...>>"logs\windows_stdout.log"
python -m app.main 1>>"logs\windows_stdout.log" 2>>"logs\windows_stderr.log"
set "EXIT_CODE=%ERRORLEVEL%"
echo [%date% %time%] FinanceOS Bot stopped with exit code %EXIT_CODE%.>>"logs\windows_stdout.log"

popd > nul
exit /b %EXIT_CODE%
