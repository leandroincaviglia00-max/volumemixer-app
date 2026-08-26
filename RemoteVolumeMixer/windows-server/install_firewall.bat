@echo off
REM Adds a Windows Firewall rule so phones on the LAN can reach the server.
REM Must be run as Administrator.
title Remote Volume Mixer - Install firewall rule

net session >nul 2>&1
if %errorLevel% neq 0 (
  echo [ERROR] Right-click this file and choose "Run as administrator".
  pause
  exit /b 1
)

echo [FIREWALL] Adding rule "Remote Volume Mixer" TCP 8765 ...
netsh advfirewall firewall delete rule name="Remote Volume Mixer" >nul 2>&1
netsh advfirewall firewall delete rule name="Remote Volume Mixer Discovery" >nul 2>&1
netsh advfirewall firewall add rule name="Remote Volume Mixer" dir=in action=allow protocol=TCP localport=8765 profile=private,domain
netsh advfirewall firewall add rule name="Remote Volume Mixer Discovery" dir=in action=allow protocol=UDP localport=8766 profile=private,domain
echo.
echo [FIREWALL] Done. TCP 8765 + UDP 8766 allowed on private/domain networks only.
echo            ^(Public networks stay blocked on purpose - LAN only.^)
pause
