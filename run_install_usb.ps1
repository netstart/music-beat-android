<#
.SYNOPSIS
    Constrói (se necessário) e instala o app no dispositivo Android conectado via USB.
.DESCRIPTION
    - Detecta o dispositivo USB via adb
    - Se o APK debug não existir, executa `gradlew assembleDebug` antes
    - Instala o APK e abre o app

.EXAMPLE
    .\install_usb.ps1
#>

# ============================= CONFIGURAÇÕES =============================
$SDK           = "C:\Android\Sdk"
$APK_PATH      = "C:\src\music-beat\bpm_app\app\build\outputs\apk\debug\app-debug.apk"
$PACKAGE_NAME  = "com.example.bpm_player"
$LAUNCHER      = "$PACKAGE_NAME/.MainActivity"
# ========================================================================

$adb     = "$SDK\platform-tools\adb.exe"
$gradlew = "bpm_app\gradlew.bat"

Write-Host ""
Write-Host "===================================================="
Write-Host "  BPM Player - Instalar via USB"
Write-Host "===================================================="
Write-Host ""

# 1. Verifica o adb
if (-not (Test-Path $adb)) {
    Write-Host "ERRO: adb não encontrado em $adb"
    exit 1
}

# 2. Garante que o servidor adb está rodando
Write-Host "[1/5] Iniciando servidor adb..."
& $adb start-server 2>&1 | Out-Null

# 3. Lista dispositivos e escolhe o USB
Write-Host "[2/5] Procurando dispositivo USB..."
$devicesOutput = & $adb devices 2>&1 | Out-String
Write-Host $devicesOutput

# Procura dispositivos USB (exclui emuladores "emulator-XXXX" e entradas "offline" / "unauthorized")
$usbDevice = $null
foreach ($line in ($devicesOutput -split "`n")) {
    $line = $line.Trim()
    if ($line -match '^(\S+)\s+device$') {
        $serial = $matches[1]
        if ($serial -notmatch '^emulator-') {
            $usbDevice = $serial
            break
        }
    }
}

if (-not $usbDevice) {
    Write-Host "ERRO: nenhum dispositivo USB autorizado encontrado."
    Write-Host "  - Conecte o celular via USB"
    Write-Host "  - Ative 'Depuração USB' nas Opções de Desenvolvedor"
    Write-Host "  - Aceite o popup de autorização RSA no celular"
    exit 1
}
Write-Host "  Dispositivo: $usbDevice"

# 3. Build (se necessário)
Write-Host "[3/5] Verificando APK..."
if (-not (Test-Path $APK_PATH)) {
    Write-Host "  APK não encontrado. Compilando..."
    Push-Location bpm_app
    if (-not (Test-Path .\gradlew.bat)) {
        Write-Host "ERRO: gradlew.bat não encontrado em bpm_app"
        Pop-Location
        exit 1
    }
    & .\gradlew.bat assembleDebug 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERRO: build falhou"
        Pop-Location
        exit 1
    }
    Pop-Location
}
if (-not (Test-Path $APK_PATH)) {
    Write-Host "ERRO: APK ainda não existe em $APK_PATH após o build."
    exit 1
}
Write-Host "  APK: $APK_PATH"

# 4. Instala o APK
Write-Host "[4/5] Instalando APK em $usbDevice..."
& $adb -s $usbDevice install -r $APK_PATH 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERRO: falha ao instalar o APK."
    exit 1
}

# 5. Abre o app
Write-Host "[5/5] Abrindo app..."
& $adb -s $usbDevice shell am start -n $LAUNCHER 2>&1 | Out-Null

Write-Host ""
Write-Host "===================================================="
Write-Host "  Pronto! App instalado e aberto em $usbDevice"
Write-Host "===================================================="
Write-Host ""
Write-Host "Dicas:"
Write-Host "  - Se o celular não apareceu, ative a Depuração USB"
Write-Host "  - Conecte em modo 'Transferência de arquivos (MTP)'"
Write-Host "  - Para listar dispositivos: adb devices"
Write-Host "  - Para ver logs: adb -s $usbDevice logcat | Select-String bpm_player"
Write-Host ""
