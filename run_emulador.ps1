<#
.SYNOPSIS
    Inicia o emulador BPM Player com aceleração WHPX (sem travar),
    disponibiliza os MP3s de C:\Users\piru\Music no emulador,
    instala e abre o app.

.EXAMPLE
    .\run_emulador.ps1
#>

# ============================= CONFIGURAÇÕES =============================
$SDK          = "C:\Android\Sdk"
$MUSIC_SOURCE = "C:\Users\piru\Music"
$APK_PATH     = "C:\src\music-beat\bpm_app\app\build\outputs\apk\debug\app-debug.apk"
$AVD_NAME     = "test_device"
$PACKAGE_NAME = "com.example.bpm_player"
$LAUNCHER     = "$PACKAGE_NAME/.MainActivity"
# ========================================================================

$adb      = "$SDK\platform-tools\adb.exe"
$emulator = "$SDK\emulator\emulator.exe"

Write-Host ""
Write-Host "===================================================="
Write-Host "  BPM Player - Emulador"
Write-Host "===================================================="
Write-Host ""

# -------------------------------------------------------------------
# 1. Mata emuladores antigos (limpa estado para evitar conflito)
# -------------------------------------------------------------------
Write-Host "[1/6] Encerrando emuladores em execução..."
Get-Process | Where-Object { $_.ProcessName -like "*emulator*" -or $_.ProcessName -like "*qemu*" } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3

# -------------------------------------------------------------------
# 2. Inicia emulador com aceleração WHPX + GPU host + 8GB RAM
# -------------------------------------------------------------------
Write-Host "[2/6] Iniciando emulador (WHPX + GPU host, 8GB RAM, 1440x3200)..."

if (-not (Test-Path $emulator)) {
    Write-Host "ERRO: emulador não encontrado em $emulator"
    exit 1
}

# -accel on = acelerao WHPX/hardware (evita emulator lento)
# -gpu host = usa GPU nativa do Windows
# -no-snapshot-load = boot limpo, ignora snapshot anterior
Start-Process -FilePath $emulator -ArgumentList "-avd $AVD_NAME -accel on -gpu host -no-snapshot-load"
Start-Sleep -Seconds 20

# -------------------------------------------------------------------
# 3. Aguarda boot completar
# -------------------------------------------------------------------
Write-Host "[3/6] Aguardando boot do Android (máx. 4 min)..."
$ready = $false
$maxTries = 120
for ($i = 0; $i -lt $maxTries; $i++) {
    $devices = & $adb devices 2>$null | Out-String
    if ($devices -match "emulator-\d+\s+device") {
        $boot = & $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null | Out-String
        if ($boot.Trim() -eq "1") { $ready = $true; break }
    }
    if ($i % 30 -eq 0) { Write-Host "  ...ainda inicializando (tentativa $i/$maxTries)..." }
    Start-Sleep -Seconds 2
}

if (-not $ready) {
    Write-Host "ERRO: Android não inicializou em tempo hábil."
    exit 1
}
Write-Host "  Android pronto!"

# -------------------------------------------------------------------
# 4. Envia os MP3s para o emulador
# -------------------------------------------------------------------
# Remove todos os MP3s antigos do emulador (atualiza conforme quantidade atual)
Write-Host "  Limpando MP3s antigos do emulador..."
& $adb -s emulator-5554 shell rm -rf /sdcard/Music/*.mp3 2>$null

# Copia TODAS as músicas atuais (seja 1 ou 100)
Write-Host "[4/6] Copiando TODAS as músicas de $MUSIC_SOURCE para o emulador..."
$mp3s = Get-ChildItem -Path $MUSIC_SOURCE -Filter "*.mp3" -File -ErrorAction SilentlyContinue
if ($mp3s.Count -eq 0) {
    Write-Host "  Nenhum MP3 encontrado em $MUSIC_SOURCE"
} else {
    foreach ($mp3 in $mp3s) {
        $dest = "/sdcard/Music/$($mp3.Name)"
        & $adb -s emulator-5554 push "$($mp3.FullName)" "/sdcard/Music/" 2>&1 | Out-Null
        Write-Host "  -> $($mp3.Name) [$(($mp3.Length / 1MB).ToString('F1')) MB]"
    }
    # Reindexa cada arquivo no MediaStore (mais confiável que broadcast no diretório)
    foreach ($mp3 in $mp3s) {
        $destPath = "file:///sdcard/Music/$($mp3.Name)"
        & $adb -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d $destPath 2>$null | Out-Null
    }
    Write-Host "  Total: $(@($mp3s).Count) MP3(s) sincronizados"
}

# -------------------------------------------------------------------
# 5. Instala o APK
# -------------------------------------------------------------------
Write-Host "[5/6] Instalando app..."
if (-not (Test-Path $APK_PATH)) {
    Write-Host "ERRO: APK não encontrado em $APK_PATH"
    Write-Host "  Baixe ou compile primeiro: gradlew :app:assembleDebug"
    exit 1
}
& $adb -s emulator-5554 install -r $APK_PATH 2>&1 | Out-Null
Write-Host "  APK instalado"

# -------------------------------------------------------------------
# 6. Abre o app
# -------------------------------------------------------------------
Write-Host "[6/6] Abrindo app..."
& $adb -s emulator-5554 shell am start -n $LAUNCHER 2>&1 | Out-Null
Write-Host "  App iniciado!"

Write-Host ""
Write-Host "===================================================="
Write-Host "  Tudo pronto! O emulador está na sua tela."
Write-Host ""
Write-Host "  Como usar:"
Write-Host "    1. Toque em 'Escolher música'"
Write-Host "    2. Menu lateral > Music"
Write-Host "    3. Selecione o arquivo"
Write-Host "    4. Arraste o slider azul BPM"
Write-Host "===================================================="
Write-Host ""