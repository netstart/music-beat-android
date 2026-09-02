@echo off
REM Script para rodar o emulador Android e disponibilizar arquivos MP3 da pasta Music
REM Para ser usado junto com o BeatTrack

set SDK_DIR=%LOCALAPPDATA%\Android\Sdk
set MUSIC_DIR=%USERPROFILE%\Music

echo ================================
echo  Iniciando emulador...
echo ================================
if exist "%SDK_DIR%\emulator\emulator.exe" (
    "%SDK_DIR%\emulator\emulator.exe" -list-avds
    echo Escolha o AVD acima e edite este script com -avd <nome>
) else (
    echo Emulador não encontrado em %SDK_DIR%\emulator\emulator.exe
    echo Verifique se o Android SDK está instalado.
)

REM Aguardar o emulador subir (simples delay)
TIMEOUT /T 10 /NOBREAK

echo ================================
echo  Disponibilizando MP3 da Music
REM Empurra arquivos .mp3 para o armazenamento externo compartilhado do emulador
echo  %MUSIC_DIR% -> /sdcard/Music no emulador (via adb push quando disponível)
if exist "%MUSIC_DIR%" (
    echo Arquivos MP3 encontrados em %MUSIC_DIR%:
    dir "%MUSIC_DIR%\*.mp3" /b 2>nul || echo (nenhum arquivo .mp3 encontrado)
) else (
    echo Pasta %MUSIC_DIR% não existe.
)

echo ================================
echo  Dica: Para usar no app, copie os MP3 via:
echo  adb push "%MUSIC_DIR%\*.mp3" /sdcard/Download/
echo ================================
