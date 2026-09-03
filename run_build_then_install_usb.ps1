# ============================================================
# build_and_install_usb.ps1
# Chama o build do APK e depois instala no Android via USB.
# ============================================================

Write-Host "=== Passo 1: Build APK ===" -ForegroundColor Cyan
& "$PSScriptRoot\run_build_apk.ps1"

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERRO: Build falhou. Abortando instalação." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "" -ForegroundColor Cyan
Write-Host "=== Passo 2: Instalar via USB ===" -ForegroundColor Cyan
& "$PSScriptRoot\run_install_usb.ps1"

if ($LASTEXITCODE -ne 0) {
    Write-Host "ERRO: Instalação via USB falhou." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "" -ForegroundColor Green
Write-Host "Build e instalação concluídos com sucesso!" -ForegroundColor Green
