<#
.SYNOPSIS
    Limpa, faz build, inicia emulador e executa o app BPM Player.
.DESCRIPTION
    Mesmas configurações do run_emulador.ps1 (WHPX, GPU host, 8GB RAM,
    AVD test_device, MP3s de C:\Users\piru\Music, APK debug).
#>

# ============================= CONFIGURAÇÕES =============================
$SDK          = "C:\Android\Sdk"
$MUSIC_SOURCE = "C:\Users\piru\Music"
$APK_PATH     = "C:\src\music-beat\bpm_app\app\build\outputs\apk\debug\app-debug.apk"
$AVD_NAME     = "test_device"
$PACKAGE_NAME = "com.example.bpm_player"
$LAUNCHER     = "$PACKAGE_NAME/.MainActivity"
# ========================================================================

$adb       = "$SDK\platform-tools\adb.exe"
$emulator  = "$SDK\emulator\emulator.exe"
$gradlew   = "bpm_app\gradlew.bat"

Write-Host ""
Write-Host "===================================================="
Write-Host "  BPM Player - Limpa, Build e Emulador"
Write-Host "===================================================="
Write-Host ""

# -------------------------------------------------------------------
# 1. Limpa e mata emuladores antigos
# -------------------------------------------------------------------
Write-Host "[1/6] Limpando build e encerrando emuladores..."
Get-Process | Where-Object { $_.ProcessName -like "*emulator*" -or $_.ProcessName -like "*qemu*" } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 2

# Limpeza do Gradle
Push-Location bpm_app
if (Test-Path .\gradlew.bat) {
    .\gradlew.bat clean 2>$null
}
Pop-Location

# -------------------------------------------------------------------
# 2. Faz o build (assembleDebug)
# -------------------------------------------------------------------
Write-Host "[2/6] Fazendo build (assembleDebug)..."
Push-Location bpm_app
if (Test-Path .\gradlew.bat) {
    .\gradlew.bat assembleDebug 2>&1
} else {
    Write-Host "ERRO: gradlew.bat não encontrado em bpm_app"
    exit 1
}
Pop-Location

# -------------------------------------------------------------------
# 3. Verifica APK
# -------------------------------------------------------------------
Write-Host "[3/6] Verificando APK..."
if (-not (Test-Path $APK_PATH)) {
    Write-Host "ERRO: APK não encontrado em $APK_PATH"
    Write-Host "  Execute gradlew assembleDebug primeiro."
    exit 1
}
Write-Host "  APK: $APK_PATH"

# -------------------------------------------------------------------
# 4. Inicia o emulador (WHPX + GPU host + 8GB RAM)
# -------------------------------------------------------------------
Write-Host ""
Write-Host "[4/6] Iniciando emulador (WHPX + GPU host, AVD $AVD_NAME)..."
if (-not (Test-Path $emulator)) {
    Write-Host "ERRO: emulador não encontrado em $emulator"
    exit 1
}

Start-Process -FilePath $emulator -ArgumentList "-avd $AVD_NAME -accel on -gpu host -no-snapshot-load"
Start-Sleep -Seconds 20

# -------------------------------------------------------------------
# 5. Aguarda boot
# -------------------------------------------------------------------
Write-Host "[5/6] Aguardando boot do Android (máx. 4 min)..."
$ready = $false
for ($i = 0; $i -lt 120; $i++) {
    $devices = & $adb devices 2>$null | Out-String
    if ($devices -match "emulator-\d+\s+device") {
        $boot = & $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null | Out-String
        if ($boot.Trim() -eq "1") { $ready = $true; break }
    }
    if ($i % 30 -eq 0) { Write-Host "  ...ainda inicializando (tentativa $i/120)..." }
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    Write-Host "ERRO: Android não inicializou em tempo hábil."
    exit 1
}
Write-Host "  Android pronto!"

# -------------------------------------------------------------------
# 6. Copia MP3s, instala APK e abre app
# -------------------------------------------------------------------
Write-Host ""
Write-Host "[6/6] Copiando TODAS as músicas, instalando e abrindo app..."

# Remove todos os MP3s antigos (sincroniza com a quantidade atual de arquivos)
Write-Host "  Limpando MP3s antigos do emulador..."
& $adb -s emulator-5554 shell rm -rf /sdcard/Music/*.mp3 2>$null

# Copia TODAS as músicas (seja 1 ou 100)
$mp3s = Get-ChildItem -Path $MUSIC_SOURCE -Filter "*.mp3" -File -ErrorAction SilentlyContinue
if ($mp3s.Count -eq 0) {
    Write-Host "  Nenhum MP3 encontrado em $MUSIC_SOURCE"
} else {
    foreach ($mp3 in $mp3s) {
        $dest = "/sdcard/Music/$($mp3.Name)"
        $result = & $adb -s emulator-5554 push "$($mp3.FullName)" "/sdcard/Music/" 2>&1 | Out-Null
        Write-Host "  -> $($mp3.Name)"
    }
    # Reindexa todos os arquivos no MediaStore para refletir quantidade real
    foreach ($mp3 in $mp3s) {
        $destPath = "file:///sdcard/Music/$($mp3.Name)"
        & $adb -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d $destPath 2>$null | Out-Null
    }
    Write-Host "  Total: $(@($mp3s).Count) MP3(s) sincronizados"
}

# Instala APK
& $adb -s emulator-5554 install -r $APK_PATH 2>&1 | Out-Null
Write-Host "  APK instalado"

# Abre app
& $adb -s emulator-5554 shell am start -n $LAUNCHER 2>&1 | Out-Null
Write-Host "  App iniciado!"

Write-Host ""
Write-Host "===================================================="
Write-Host "  Tudo pronto! O emulador está na sua tela."
Write-Host "===================================================="
Write-Host ""
Write-Host "Como usar:"
Write-Host "  1. Toque em 'Escolher música'"
Write-Host "  2. Menu lateral > Music"
Write-Host "  3. Selecione o arquivo"
Write-Host "  4. Arraste o slider azul BPM"
Write-Host ""
