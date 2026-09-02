@echo off
REM ============================================================
REM  setup_android_sdk.bat
REM  Instala componentes faltantes do Android SDK em C:\Android\Sdk
REM ============================================================

setlocal enabledelayedexpansion

set SDK_PATH=C:\Android\Sdk
set SDKMANAGER=%SDK_PATH%\cmdline-tools\latest\bin\sdkmanager.bat

echo ============================================================
echo  Setup Android SDK para BeatTrack
echo ============================================================
echo SDK Path: %SDK_PATH%
echo ------------------------------------------------------------

REM Verificar se cmdline-tools existe
if not exist "%SDK_PATH%\cmdline-tools" (
    echo [INFO] cmdline-tools nao encontrado. Baixando...
    powershell -Command ^
        "$url='https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'; $out='%TEMP%\cmdline-tools.zip'; Invoke-WebRequest -Uri $url -OutFile $out; Expand-Archive -Path $out -DestinationPath '%SDK_PATH%\cmdline-tools' -Force; Move-Item -Path '%SDK_PATH%\cmdline-tools\cmdline-tools' -Destination '%SDK_PATH%\cmdline-tools\latest' -Force"
    if errorlevel 1 (
        echo [ERRO] Falha ao baixar cmdline-tools.
        exit /b 1
    )
    echo [OK] cmdline-tools instalado.
)

if not exist "%SDKMANAGER%" (
    echo [ERRO] sdkmanager ainda nao encontrado apos install.
    exit /b 1
)

echo [INFO] Aceitando licencas...
echo y | "%SDKMANAGER%" --licenses >nul 2>&1

echo [INFO] Instalando componentes necessarios...
"%SDKMANAGER%" --install ^
    "platform-tools" ^
    "emulator" ^
    "build-tools;34.0.0" ^
    "platforms;android-34" ^
    "system-images;android-34;google_apis;x86_64" ^
    "cmdline-tools;latest"

if errorlevel 1 (
    echo [ERRO] Falha ao instalar componentes.
    exit /b 1
)

echo [OK] Componentes instalados.

echo [INFO] Criando AVD 'beat_avd'...
"%SDK_PATH%\cmdline-tools\latest\bin\avdmanager.bat" create avd -n beat_avd -k "system-images;android-34;google_apis;x86_64" --force

if errorlevel 1 (
    echo [AVISO] AVD pode ja existir ou falhou.
)

echo.
echo ============================================================
echo  SDK PRONTO!
echo  Agora rode: run_beat_track.bat
echo ============================================================

endlocal