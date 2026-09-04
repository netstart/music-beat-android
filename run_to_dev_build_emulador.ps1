#
# .SYNOPSIS
#     Executa run_to_dev_build_apk.ps1 e depois run_emulador.ps1
# .DESCRIPTION
#     Script sequencial de build dev (rápido) + execução do emulador
#

Write-Host "=== [1/3] Limpando APKs ==="
.\run_clean_apk.ps1

Write-Host ""
Write-Host "=== [2/3] Build dev (rápido) ==="
.\run_to_dev_build_apk.ps1
if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "Build falhou. Abortando execução do emulador."
    exit 1
}

Write-Host ""
Write-Host "=== [3/3] Iniciando emulador ==="
.\run_emulador.ps1
