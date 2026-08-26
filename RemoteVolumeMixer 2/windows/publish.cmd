@echo off
REM ============================================================
REM  Remote Volume Mixer - build di un EXE distribuibile
REM  Genera un singolo RemoteVolumeMixer.exe self-contained:
REM  l'utente finale NON deve installare il runtime .NET.
REM  Output: windows\publish\RemoteVolumeMixer.exe
REM ============================================================
setlocal
cd /d "%~dp0"

where dotnet >nul 2>nul
if errorlevel 1 (
  echo [ERRORE] .NET SDK 8 non trovato. Installalo da https://dotnet.microsoft.com/download/dotnet/8.0
  exit /b 1
)

echo [1/2] Ripristino pacchetti...
dotnet restore RemoteVolumeMixer\RemoteVolumeMixer.csproj || exit /b 1

echo [2/2] Publish self-contained (win-x64)...
dotnet publish RemoteVolumeMixer\RemoteVolumeMixer.csproj ^
  -c Release ^
  -r win-x64 ^
  --self-contained true ^
  -p:PublishSingleFile=true ^
  -p:IncludeNativeLibrariesForSelfExtract=true ^
  -p:EnableCompressionInSingleFile=true ^
  -p:DebugType=none ^
  -o publish || exit /b 1

echo.
echo Fatto: %CD%\publish\RemoteVolumeMixer.exe
endlocal
