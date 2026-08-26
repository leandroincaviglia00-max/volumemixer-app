@echo off
REM Removes the Windows Firewall rules created by install_firewall.bat.
title Remote Volume Mixer - Remove firewall rule

net session >nul 2>&1
if %errorLevel% neq 0 (
  echo [ERROR] Right-click this file and choose "Run as administrator".
  pause
  exit /b 1
)

echo [FIREWALL] Removing rules ...
netsh advfirewall firewall delete rule name="Remote Volume Mixer"
netsh advfirewall firewall delete rule name="Remote Volume Mixer Discovery"
echo.
echo [FIREWALL] Done.
pause
