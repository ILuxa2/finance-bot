@echo off
chcp 65001 > nul
setlocal

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$taskName = 'FinanceOS Bot'; if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) { Unregister-ScheduledTask -TaskName $taskName -Confirm:$false }"

if "%ERRORLEVEL%"=="0" (
  echo Task "FinanceOS Bot" removed if it existed.
) else (
  echo Failed to remove task "FinanceOS Bot".
)

exit /b %ERRORLEVEL%
