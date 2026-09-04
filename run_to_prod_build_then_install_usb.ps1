#
# .SYNOPSIS
#     Executa run_to_prod_build_apk.ps1 e depois run_install_usb_device.ps1
# .DESCRIPTION
#     Script sequencial de build + instalação via USB para o BeatTrack
#

Write-Host "=== Limpando APKs anteriores ==="
.\run_clean_apk.ps1

Write-Host "=== Iniciando build do APK ==="
.\run_to_prod_build_apk.ps1
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Build falhou. Abortando instalação via USB."
    exit 1
}

Write-Host ""
Write-Host "=== Iniciando instalação via USB ==="
.\run_install_usb_device.ps1 -ApkPath "C:\src\music-beat\bpm_player-release.apk"
