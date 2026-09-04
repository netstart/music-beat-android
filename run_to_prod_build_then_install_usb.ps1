#
# .SYNOPSIS
#     Executa run_to_prod_build_apk.ps1 e depois run_install_usb_device.ps1
# .DESCRIPTION
#     Script sequencial de build + instalação via USB para o BeatTrack
#

Write-Host "=== Iniciando build do APK ==="
.\run_to_prod_build_apk.ps1

Write-Host ""
Write-Host "=== Iniciando instalação via USB ==="
.\run_install_usb_device.ps1
