@echo off
chcp 65001 > nul
setlocal

powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$processes = Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'python.exe' -or $_.Name -eq 'pythonw.exe') -and $_.CommandLine -like '*app.main*' }; foreach ($process in $processes) { Stop-Process -Id $process.ProcessId -Force }"

exit /b %ERRORLEVEL%
