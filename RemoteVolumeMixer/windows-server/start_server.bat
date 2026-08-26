@echo off
setlocal
title Remote Volume Mixer Server
cd /d "%~dp0"

echo ========================================
echo  REMOTE VOLUME MIXER - STARTING
echo ========================================
echo.

REM ---- locate python -------------------------------------------------
set PY=
where py >nul 2>nul && set PY=py -3
if "%PY%"=="" (where python >nul 2>nul && set PY=python)
if "%PY%"=="" (
  echo [ERROR] Python was not found in PATH.
  echo         Install Python 3.11 - 3.13 from https://www.python.org/downloads/
  echo         and tick "Add python.exe to PATH".
  pause
  exit /b 1
)

REM ---- first run: create a local venv and install deps ---------------
if not exist "venv\Scripts\python.exe" (
  echo [SETUP] Creating virtual environment...
  %PY% -m venv venv || goto :fail
  echo [SETUP] Installing dependencies ^(one time only^)...
  "venv\Scripts\python.exe" -m pip install --upgrade pip >nul
  "venv\Scripts\python.exe" -m pip install -r requirements.txt || goto :fail
  echo [SETUP] Done.
  echo.
)

"venv\Scripts\python.exe" server.py %*
echo.
echo [SERVER] Stopped.
pause
exit /b 0

:fail
echo.
echo [ERROR] Setup failed. Try running this file as Administrator,
echo         or install the dependencies manually:
echo             pip install -r requirements.txt
pause
exit /b 1
