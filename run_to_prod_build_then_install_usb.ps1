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
# Busca o APK mais recente na árvore outputs para passar ao script de instalação
$apkFiles = Get-ChildItem -Path "C:\src\music-beat\bpm_app\app\build\outputs" -Recurse -Filter *.apk -File | Sort-Object LastWriteTime -Descending
$apkPath = if ($apkFiles) { $apkFiles[0].FullName } else { "" }
if ($apkPath) { Write-Host "APK selecionado para instalação: $apkPath" }
.\run_install_usb_device.ps1 -ApkPath $apkPath
