#
# .SYNOPSIS
#     Executa run_build_apk.ps1 e depois run_install_usb.ps1
# .DESCRIPTION
#     Script sequencial de build + instalação via USB para o BeatTrack
#

Write-Host "=== Iniciando build do APK ==="
.\run_build_apk.ps1

Write-Host ""
Write-Host "=== Iniciando instalação via USB ==="
.\run_install_usb.ps1
