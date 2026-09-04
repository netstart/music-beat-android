<#
.SYNOPSIS
    Inicia o emulador com captura de log (para investigar travamentos)
#>

$SDK          = "C:\Android\Sdk"
$AVD_NAME     = "test_device"
$PACKAGE_NAME = "com.example.bpm_player"
$LAUNCHER     = "$PACKAGE_NAME/.MainActivity"
$LOG_DIR      = "C:\src\music-beat\logs"

$adb      = "$SDK\platform-tools\adb.exe"
$emulator = "$SDK\emulator\emulator.exe"

# Busca recursiva de APK na pasta outputs
$apkFiles = Get-ChildItem -Path "C:\src\music-beat\bpm_app\app\build\outputs" -Recurse -Filter *.apk -File | Sort-Object LastWriteTime -Descending
if ($apkFiles) {
    $APK_PATH = $apkFiles[0].FullName
    Write-Host "APK encontrado: $APK_PATH"
} else {
    $APK_PATH = $null
    Write-Host "AVISO: Nenhum .apk encontrado em C:\src\music-beat\bpm_app\app\build\outputs"
}

New-Item -ItemType Directory -Force -Path $LOG_DIR | Out-Null

$emulatorLog = "$LOG_DIR\emulator.log"
$logcatLog   = "$LOG_DIR\logcat.log"
$crashLog    = "$LOG_DIR\crash.log"
$tombsLog    = "$LOG_DIR\tombstones.log"

Write-Host ""
Write-Host "===================================================="
Write-Host "  BPM Player - Emulador COM LOGS"
Write-Host "===================================================="
Write-Host "  Logs em: $LOG_DIR"
Write-Host "===================================================="
Write-Host ""

# Mata processos antigos
Get-Process | Where-Object { $_.ProcessName -like "*emulator*" -or $_.ProcessName -like "*qemu*" -or $_.ProcessName -like "adb" } | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 3

# Limpa log antigo
"" | Set-Content $emulatorLog
"" | Set-Content $logcatLog
"" | Set-Content $crashLog

Write-Host "[1/5] Limpando crash logs anteriores (tombstones)..."
& $adb -s emulator-5554 shell rm -rf /data/tombstones 2>$null
& $adb -s emulator-5554 shell rm -f /data/anr/*.txt 2>$null

# Inicia emulador com captura de log verbose
Write-Host "[2/5] Iniciando emulador (verbose) e gravando em $emulatorLog..."
# -verbose: mostra muita info de inicializacao
# -no-window: NAO usa, queremos ver a janela
$emulatorArgs = @(
    "-avd", $AVD_NAME,
    "-accel", "on",
    "-gpu", "host",
    "-no-snapshot-load",
    "-verbose"
)
$emulatorProc = Start-Process -FilePath $emulator -ArgumentList $emulatorArgs -RedirectStandardOutput $emulatorLog -RedirectStandardError $emulatorLog -NoNewWindow -PassThru
Write-Host "  Emulator PID: $($emulatorProc.Id)"

# Aguarda boot
Write-Host "[3/5] Aguardando boot do Android..."
$ready = $false
$maxTries = 90
for ($i = 0; $i -lt $maxTries; $i++) {
    $devices = & $adb devices 2>$null | Out-String
    if ($devices -match "emulator-\d+\s+device") {
        $boot = & $adb -s emulator-5554 shell getprop sys.boot_completed 2>$null | Out-String
        if ($boot.Trim() -eq "1") { $ready = $true; break }
    }
    if ($i % 15 -eq 0) { Write-Host "  ...boot tentativa $i/$maxTries" }
    Start-Sleep -Seconds 2
}
if (-not $ready) { Write-Host "  ERRO: boot falhou"; exit 1 }

Write-Host "  Android pronto"

# Limpa logcat antigo e comeca captura
Write-Host "[4/5] Iniciando captura de logcat (filtrando por $PACKAGE_NAME, ANR, fatals)..."
& $adb -s emulator-5554 logcat -c

# Captura em background: tudo + filtrado
$logcatArgs = @("-s", "AndroidRuntime:E", "ActivityManager:W", "DEBUG:E", "tombstoned:E", "ANR:E", "DEBUG-CRASH:E", "BPM_Player:V", "System.err:W", "LibVLC:E", "ExoPlayer:E", "MediaCodec:E")
Start-Process -FilePath $adb -ArgumentList @("-s", "emulator-5554", "logcat", "-v", "time", "*:V") -RedirectStandardOutput $logcatLog -RedirectStandardError $logcatLog -NoNewWindow

# Instala o APK
Write-Host "[5/5] Instalando e abrindo o app..."
if (Test-Path $APK_PATH) {
    & $adb -s emulator-5554 install -r $APK_PATH 2>&1 | Out-Null
    Write-Host "  APK instalado"
} else {
    Write-Host "  APK nao encontrado em $APK_PATH - compile primeiro"
    exit 1
}

# Abre o app
& $adb -s emulator-5554 shell am start -n $LAUNCHER 2>&1 | Out-Null
Write-Host "  App iniciado"
Write-Host ""
Write-Host "===================================================="
Write-Host "  App rodando. Monitorando por 120s..."
Write-Host ""
Write-Host "  Para parar antes: Ctrl+C"
Write-Host "  Logs em: $LOG_DIR"
Write-Host "  Comandos uteis:"
Write-Host "    Get-Content $logcatLog -Wait"
Write-Host "    Get-Content $crashLog"
Write-Host "===================================================="
Write-Host ""

# Monitora o processo do app
$appPid = $null
$dead = $false
$max = 120
for ($i = 0; $i -lt $max; $i++) {
    $pidof = & $adb -s emulator-5554 shell pidof $PACKAGE_NAME 2>$null | Out-String
    $pidof = $pidof.Trim()
    if ($pidof -ne "" -and $null -ne $pidof) {
        $appPid = $pidof
        Write-Host "  [${i}s] app PID: $appPid (vivo)"
    } else {
        if (-not $dead -and $appPid) {
            Write-Host "  [${i}s] *** APP MORREU! (estava PID $appPid) ***"
            $dead = $true
        } elseif (-not $appPid) {
            Write-Host "  [${i}s] aguardando app..."
        } else {
            Write-Host "  [${i}s] app continua morto"
        }
    }
    Start-Sleep -Seconds 1
}

# Captura dados do crash
Write-Host ""
Write-Host "=== COLETANDO DADOS DO CRASH ==="

# Dump da pilha do Android
& $adb -s emulator-5554 shell "dumpsys dropbox --print" 2>&1 | Out-File -Append -FilePath $crashLog
& $adb -s emulator-5554 shell "ls -la /data/anr/" 2>&1 | Out-File -Append -FilePath $crashLog
& $adb -s emulator-5554 shell "cat /data/anr/*.txt 2>/dev/null" 2>&1 | Out-File -Append -FilePath $crashLog

# Tombstones
& $adb -s emulator-5554 shell "ls -la /data/tombstones/" 2>&1 | Out-File -Append -FilePath $crashLog
& $adb -s emulator-5554 shell "cat /data/tombstones/* 2>/dev/null" 2>&1 | Out-File -FilePath $tombsLog

# logcat final com tag focado
& $adb -s emulator-5554 logcat -d -b crash -v time 2>&1 | Out-File -Append -FilePath $crashLog
& $adb -s emulator-5554 logcat -d -v time *:E 2>&1 | Out-File -FilePath "$LOG_DIR\logcat_errors.log"

Write-Host "  Crash log:   $crashLog"
Write-Host "  Tombstones:  $tombsLog"
Write-Host "  Logcat err:  $LOG_DIR\logcat_errors.log"
Write-Host "  Logcat full: $logcatLog"
Write-Host "  Emulator:    $emulatorLog"
Write-Host ""

# Mostra resumo dos erros
Write-Host "=== ERROS DO LOGCAT (últimas 30) ==="
Get-Content "$LOG_DIR\logcat_errors.log" -Tail 30

Write-Host ""
Write-Host "=== ÚLTIMAS 20 LINHAS DO CRASH LOG ==="
Get-Content $crashLog -Tail 20

Write-Host ""
Write-Host "=== CRASH NO LOGCAT (filtrado pelo package) ==="
Get-Content $logcatLog | Select-String -Pattern "FATAL|AndroidRuntime|$PACKAGE_NAME|ANR " | Select-Object -Last 30

Write-Host ""
Write-Host "Pressione qualquer tecla para fechar o emulador..."
$null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

# Limpa
Get-Process | Where-Object { $_.ProcessName -like "*emulator*" -or $_.ProcessName -like "*qemu*" } | Stop-Process -Force -ErrorAction SilentlyContinue
Get-Process | Where-Object { $_.ProcessName -like "adb" -and $_.MainWindowTitle -eq "" } | Stop-Process -Force -ErrorAction SilentlyContinue
