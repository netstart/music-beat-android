@echo off
REM ============================================================
REM  run_beat_track.bat
REM  Executa o BeatTrack no emulador com MP3s da pasta Music
REM ============================================================

setlocal enabledelayedexpansion

REM --- Configurações (edite se necessário) ---
set AVD_NAME=beat_avd
set SDK_PATH=C:\Android\Sdk
set PROJECT_PATH=C:\src\music-beat\bpm_app
set EMU_MEMORY=4096
set EMU_CORES=4

REM --- Flags opcionais ---
set WIPE_DATA=false
set HEADLESS=false
set NO_BUILD=false

REM Parse de argumentos simples
for %%a in (%*) do (
    if /i "%%a"=="--wipe-data" set WIPE_DATA=true
    if /i "%%a"=="--headless" set HEADLESS=true
    if /i "%%a"=="--no-build" set NO_BUILD=true
    if /i "%%a"=="--avd" set GET_AVD=1
    if defined GET_AVD if not "%%a"=="--avd" set AVD_NAME=%%a&set GET_AVD=
)

echo ============================================================
echo  BeatTrack - Execucao no Emulador
echo ============================================================
echo AVD: %AVD_NAME%
echo SDK: %SDK_PATH%
echo Projeto: %PROJECT_PATH%
echo Memoria: %EMU_MEMORY% MB | Cores: %EMU_CORES%
echo ------------------------------------------------------------

REM --- 1. Verificar SDK ---
echo [1/7] Verificando SDK em %SDK_PATH%...
if not exist "%SDK_PATH%" (
    echo [ERRO] SDK nao encontrado em %SDK_PATH%
    echo Instale o Android Studio ou ajuste SDK_PATH no script.
    exit /b 1
)

set EMULATOR_EXE=%SDK_PATH%\emulator\emulator.exe
set ADB_EXE=%SDK_PATH%\platform-tools\adb.exe
set SDKMANAGER=%SDK_PATH%\cmdline-tools\latest\bin\sdkmanager.bat

set MISSING=
if not exist "%EMULATOR_EXE%" set MISSING=%MISSING% emulator\emulator.exe
if not exist "%ADB_EXE%" set MISSING=%MISSING% platform-tools\adb.exe
if not exist "%SDKMANAGER%" set MISSING=%MISSING% cmdline-tools\latest\bin\sdkmanager.bat

if not "%MISSING%"=="" (
    echo [AVISO] Componentes do SDK faltando: %MISSING%
    echo Instale via Android Studio > SDK Manager ou rode:
    echo   %SDKMANAGER% --install "emulator" "platform-tools" "build-tools;34.0.0" "cmdline-tools;latest"
    exit /b 1
)
echo [OK] SDK completo.

REM --- 2. Verificar AVD ---
echo [2/7] Verificando AVD '%AVD_NAME%'...
%EMULATOR_EXE% -list-avds > "%TEMP%\avd_list.txt" 2>nul
findstr /x /c:"%AVD_NAME%" "%TEMP%\avd_list.txt" >nul
if errorlevel 1 (
    echo [ERRO] AVD '%AVD_NAME%' nao existe. AVDs disponiveis:
    type "%TEMP%\avd_list.txt"
    echo.
    echo Crie com: avdmanager create avd -n %AVD_NAME% -k "system-images;android-34;google_apis;x86_64"
    exit /b 1
)
echo [OK] AVD existe.

REM --- 3. Compilar APK ---
if "%NO_BUILD%"=="false" (
    echo [3/7] Compilando APK (debug)...
    cd /d "%PROJECT_PATH%"
    if exist "gradlew.bat" (
        call gradlew.bat assembleDebug --no-daemon
    ) else if exist "gradlew" (
        call gradlew assembleDebug --no-daemon
    ) else (
        echo [ERRO] gradlew nao encontrado em %PROJECT_PATH%
        exit /b 1
    )
    if errorlevel 1 (
        echo [ERRO] Falha na compilacao.
        exit /b 1
    )
    echo [OK] APK compilado.
) else (
    echo [3/7] Pulando compilacao (--no-build).
)

set APK_PATH=%PROJECT_PATH%\app\build\outputs\apk\debug\app-debug.apk
if not exist "%APK_PATH%" (
    echo [ERRO] APK nao encontrado em %APK_PATH%
    echo Rode sem --no-build primeiro.
    exit /b 1
)

REM --- 4. Iniciar emulador ---
echo [4/7] Iniciando emulador '%AVD_NAME%' (RAM: %EMU_MEMORY%MB, Cores: %EMU_CORES%)...
set EMU_ARGS=-avd %AVD_NAME% -memory %EMU_MEMORY% -cores %EMU_CORES%
if "%WIPE_DATA%"=="true" set EMU_ARGS=%EMU_ARGS% -wipe-data
if "%HEADLESS%"=="true" set EMU_ARGS=%EMU_ARGS% -no-window

start "" "%EMULATOR_EXE%" %EMU_ARGS%
echo [OK] Emulador iniciado. Aguardando boot...

REM Aguardar emulador estar pronto (max 120s)
set MAX_WAIT=120
set ELAPSED=0
:WAIT_LOOP
%ADB_EXE% devices | findstr /r "emulator-[0-9][0-9]*[[:space:]]device" >nul
if not errorlevel 1 (
    echo [OK] Emulador pronto.
    goto :EMU_READY
)
if %ELAPSED% geq %MAX_WAIT% (
    echo [ERRO] Timeout aguardando emulador (%MAX_WAIT% s).
    exit /b 1
)
timeout /t 2 /nobreak >nul
set /a ELAPSED+=2
goto :WAIT_LOOP

:EMU_READY

REM --- 5. Instalar APK ---
echo [5/7] Instalando APK no emulador...
%ADB_EXE% install -r -d "%APK_PATH%" >nul
if errorlevel 1 (
    echo [ERRO] Falha ao instalar APK.
    exit /b 1
)
echo [OK] APK instalado.

REM --- 6. Copiar MP3s da pasta Music ---
echo [6/7] Copiando MP3s de %USERPROFILE%\Music para /sdcard/Music...
%ADB_EXE% shell "mkdir -p /sdcard/Music" >nul

set MP3_COUNT=0
for %%f in ("%USERPROFILE%\Music\*.mp3") do (
    if exist "%%f" (
        set /a MP3_COUNT+=1
        echo Enviando: %%~nxf...
        %ADB_EXE% push "%%f" "/sdcard/Music/%%~nxf" >nul
    )
)

if %MP3_COUNT% equ 0 (
    echo [AVISO] Nenhum MP3 encontrado em %USERPROFILE%\Music
) else (
    echo [OK] %MP3_COUNT% arquivo(s) MP3 copiado(s).
    REM Refresh MediaStore
    %ADB_EXE% shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music" >nul
)

REM --- 7. Iniciar o app ---
echo [7/7] Iniciando BeatTrack...
%ADB_EXE% shell "am start -n com.example.bpm_player/.MainActivity" >nul
echo [OK] BeatTrack iniciado!

echo.
echo ============================================================
echo  PRONTO - App rodando no emulador
echo  MP3s disponiveis no app via 'Escolher musica'
echo  Para parar: adb emu kill
echo ============================================================

endlocal