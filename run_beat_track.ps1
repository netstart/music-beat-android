<#
.SYNOPSIS
    Executa o BeatTrack no emulador Android disponibilizando MP3s da pasta Music do usuário.
.DESCRIPTION
    Este script verifica o ambiente, compila o APK se necessário, inicia o emulador e
    copia os MP3s da pasta Music do usuário para o armazenamento do emulador.
.NOTES
    Requisitos:
    - Android SDK completo (emulator, platform-tools, build-tools, cmdline-tools)
    - AVD já criado (ex: avdmanager create avd -n beat_avd -k "system-images;android-34;google_apis;x86_64")
    - Gradle disponível (ou use ./gradlew se estiver no projeto)
#>

param(
    [string]$AvdName = "beat_avd",
    [string]$SdkPath = "C:\Android\Sdk",
    [string]$ProjectPath = "C:\src\music-beat\bpm_app",
    [int]$EmulatorMemory = 4096,
    [int]$EmulatorCores = 4,
    [switch]$NoBuild,
    [switch]$WipeData,
    [switch]$Headless
)

$ErrorActionPreference = "Stop"

function Write-Status($msg) { Write-Host "[INFO] $msg" -ForegroundColor Cyan }
function Write-Warn($msg) { Write-Host "[WARN] $msg" -ForegroundColor Yellow }
function Write-ErrorAndExit($msg) { Write-Host "[ERRO] $msg" -ForegroundColor Red; exit 1 }
function Write-Success($msg) { Write-Host "[OK] $msg" -ForegroundColor Green }

# --- 1. Verificar SDK ---
Write-Status "Verificando Android SDK em $SdkPath..."
if (-not (Test-Path $SdkPath)) {
    Write-ErrorAndExit "SDK não encontrado em $SdkPath. Instale o Android Studio ou defina -SdkPath."
}

$emulatorExe = Join-Path $SdkPath "emulator\emulator.exe"
$adbExe = Join-Path $SdkPath "platform-tools\adb.exe"
$sdkManager = Join-Path $SdkPath "cmdline-tools\latest\bin\sdkmanager.bat"

$missing = @()
if (-not (Test-Path $emulatorExe)) { $missing += "emulator\emulator.exe" }
if (-not (Test-Path $adbExe)) { $missing += "platform-tools\adb.exe" }
if (-not (Test-Path $sdkManager)) { $missing += "cmdline-tools\latest\bin\sdkmanager.bat" }

if ($missing.Count -gt 0) {
    Write-Warn "Componentes do SDK faltando: $($missing -join ', ')"
    Write-Warn "Instale via Android Studio > SDK Manager ou:"
    Write-Warn "  $sdkManager --install 'emulator' 'platform-tools' 'build-tools;34.0.0' 'cmdline-tools;latest'"
    Write-ErrorAndExit "SDK incompleto. Complete a instalação e rode novamente."
}

Write-Success "SDK completo encontrado."

# --- 2. Verificar AVD ---
Write-Status "Verificando AVD '$AvdName'..."
$avdList = & $emulatorExe -list-avds 2>$null
if ($avdList -notcontains $AvdName) {
    Write-Warn "AVD '$AvdName' não existe. AVDs disponíveis:"
    $avdList | ForEach-Object { Write-Host "  $_" }
    Write-ErrorAndExit "Crie o AVD primeiro: avdmanager create avd -n $AvdName -k 'system-images;android-34;google_apis;x86_64'"
}
Write-Success "AVD '$AvdName' existe."

# --- 3. Compilar APK se não for --NoBuild ---
if (-not $NoBuild) {
    Write-Status "Compilando APK (debug)..."
    Push-Location $ProjectPath
    try {
        if (Test-Path "gradlew.bat") {
            & ".\gradlew.bat" assembleDebug --no-daemon
        } elseif (Test-Path "gradlew") {
            & "./gradlew" assembleDebug --no-daemon
        } else {
            Write-ErrorAndExit "gradlew não encontrado em $ProjectPath"
        }
        Write-Success "APK compilado."
    } catch {
        Write-ErrorAndExit "Falha na compilação: $_"
    } finally {
        Pop-Location
    }
}

$apkPath = Join-Path $ProjectPath "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    Write-ErrorAndExit "APK não encontrado em $apkPath. Rode sem --NoBuild primeiro."
}

# --- 4. Iniciar emulador ---
Write-Status "Iniciando emulador '$AvdName' (RAM: ${EmulatorMemory}MB, Cores: $EmulatorCores)..."
$emuArgs = @("-avd", $AvdName, "-memory", $EmulatorMemory.ToString(), "-cores", $EmulatorCores.ToString())
if ($WipeData) { $emuArgs += "-wipe-data" }
if ($Headless) { $emuArgs += "-no-window" }

$emuProcess = Start-Process $emulatorExe -ArgumentList $emuArgs -PassThru
Write-Success "Emulador iniciado (PID: $($emuProcess.Id)). Aguardando boot..."

# Aguardar emulador estar pronto
$maxWait = 120
$start = Get-Date
while ((Get-Date) - $start).TotalSeconds -lt $maxWait {
    $devices = & $adbExe devices 2>$null
    if ($devices -match "emulator-\d+\s+device") {
        Write-Success "Emulador pronto."
        break
    }
    Start-Sleep 2
}
if (-not ($devices -match "emulator-\d+\s+device")) {
    Write-ErrorAndExit "Timeout aguardando emulador ($maxWait s)."
}

# --- 5. Instalar APK ---
Write-Status "Instalando APK no emulador..."
& $adbExe install -r -d $apkPath | Out-Null
Write-Success "APK instalado."

# --- 6. Copiar MP3s da pasta Music do usuário ---
Write-Status "Copiando MP3s de $env:USERPROFILE\Music para /sdcard/Music no emulador..."
$musicDir = Join-Path $env:USERPROFILE "Music"
$mp3Files = Get-ChildItem $musicDir -Filter "*.mp3" -ErrorAction SilentlyContinue
if ($mp3Files.Count -eq 0) {
    Write-Warn "Nenhum MP3 encontrado em $musicDir"
} else {
    Write-Status "Encontrados $($mp3Files.Count) arquivo(s) MP3."
    # Criar pasta no emulador
    & $adbExe shell "mkdir -p /sdcard/Music" | Out-Null
    foreach ($mp3 in $mp3Files) {
        $dest = "/sdcard/Music/$($mp3.Name)"
        Write-Status "Enviando: $($mp3.Name)..."
        & $adbExe push $mp3.FullName $dest | Out-Null
    }
    Write-Success "MP3s copiados para /sdcard/Music no emulador."
    # Refresh MediaStore para o app ver os arquivos
    & $adbExe shell "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music" | Out-Null
}

# --- 7. Iniciar o app ---
Write-Status "Iniciando BeatTrack..."
& $adbExe shell "am start -n com.example.bpm_player/.MainActivity" | Out-Null
Write-Success "BeatTrack iniciado no emulador!"

Write-Host ""
Write-Host "=================== PRONTO ===================" -ForegroundColor Green
Write-Host "App rodando. MP3s disponíveis no app via 'Escolher música'."
Write-Host "Para parar o emulador: adb emu kill"
Write-Host "==============================================" -ForegroundColor Green