#
# .SYNOPSIS
#     Executa clean, build dev e instala via USB.
# .DESCRIPTION
#     Limpa APKs, compila build dev rápido e instala no dispositivo USB.
#

Write-Host "=== [1/3] Limpando APKs ==="
.\run_clean_apk.ps1

Write-Host ""
Write-Host "=== [2/3] Build dev (rápido) ==="
.\run_to_dev_build_apk.ps1
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Build dev falhou. Abortando instalação."
    exit 1
}

Write-Host ""
Write-Host "=== [3/3] Instalando via USB ==="
.\run_install_usb_device.ps1 -ApkPath "C:\src\music-beat\bpm_player.apk"
