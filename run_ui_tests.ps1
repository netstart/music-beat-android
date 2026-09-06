<#
.SYNOPSIS
    Executa testes instrumentados de UI do BPM Player no emulador.
    Usa o emulador test_device (esperado já rodando via run_emulador.ps1)
    e executa MainActivityPlayPauseTest.

.EXAMPLE
    .\run_ui_tests.ps1
#>

# ============================= CONFIGURAÇÕES =============================
$SDK       = "C:\Android\Sdk"
$PACKAGE   = "com.example.bpm_player"
$TEST_CLASS = "$PACKAGE.MainActivityPlayPauseTest"
$GRADLEW   = ".\bpm_app\gradlew.bat"
# ========================================================================

Write-Host ""
Write-Host "===================================================="
Write-Host "  BPM Player - Testes de UI Instrumentados"
Write-Host "===================================================="
Write-Host ""

# Verifica se o emulador está rodando
$adb = "$SDK\platform-tools\adb.exe"
$devices = & $adb devices 2>$null | Out-String
if ($devices -notmatch "emulator-\d+\s+device") {
    Write-Host "ERRO: Nenhum emulador detectado."
    Write-Host "  Execute '.\run_emulador.ps1' primeiro para iniciar o emulador."
    exit 1
}
Write-Host "Emulador detectado."

# 1. Compila os testes instrumentados
Write-Host ""
Write-Host "[1/3] Compilando testes instrumentados..."
& $GRADLEW -p bpm_app assembleDebugAndroidTest --no-daemon 2>&1 | Out-String | Select-Object -Last 1

# 2. Executa os testes de UI no emulador
Write-Host ""
Write-Host "[2/3] Executando $TEST_CLASS no emulador..."
& $GRADLEW -p bpm_app connectedDebugAndroidTest --tests "$TEST_CLASS" --no-daemon 2>&1 |
    Tee-Object -FilePath "logs/ui_tests.log" | Select-Object -Last 30

# 3. Resumo do resultado
Write-Host ""
Write-Host "[3/3] Resultado:"
if (Test-Path "logs/ui_tests.log") {
    $result = Get-Content "logs/ui_tests.log" -Tail 15
    $result | ForEach-Object { Write-Host "  $_" }
}
Write-Host ""
Write-Host "Log completo: logs/ui_tests.log"
Write-Host "Teste: bpm_app/app/src/androidTest/java/com/example/bpm_player/MainActivityPlayPauseTest.kt"
Write-Host "Asset: bpm_app/app/src/main/assets/test/test_music.mp3"
