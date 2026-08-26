@echo off
REM Build veloce + avvio con log a video (utile per il primo test).
setlocal
cd /d "%~dp0"
dotnet build RemoteVolumeMixer\RemoteVolumeMixer.csproj -c Debug || exit /b 1
echo.
echo Avvio con log in console (CTRL+C o menu tray > Exit per uscire)...
dotnet run --project RemoteVolumeMixer\RemoteVolumeMixer.csproj -c Debug -- --console --verbose
endlocal
