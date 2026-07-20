@echo off
chcp 65001 > nul
setlocal

set "SCRIPT_DIR=%~dp0"
pushd "%SCRIPT_DIR%..\.." > nul
set "PROJECT_ROOT=%CD%"
set "START_SCRIPT=%PROJECT_ROOT%\scripts\windows\start_bot.bat"

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$taskName = 'FinanceOS Bot';" ^
  "$root = $env:PROJECT_ROOT;" ^
  "$script = $env:START_SCRIPT;" ^
  "$argument = '/c ""' + $script + '""';" ^
  "$action = New-ScheduledTaskAction -Execute 'cmd.exe' -Argument $argument -WorkingDirectory $root;" ^
  "$trigger = New-ScheduledTaskTrigger -AtLogOn;" ^
  "$settings = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries;" ^
  "try { Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -RunLevel Highest -Force | Out-Null }" ^
  "catch { Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null }"

set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" (
  echo Task "FinanceOS Bot" installed or updated.
) else (
  echo Failed to install task "FinanceOS Bot".
)

popd > nul
exit /b %EXIT_CODE%
