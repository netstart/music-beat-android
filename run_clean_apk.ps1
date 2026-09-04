#
# .SYNOPSIS
#     Varre a raiz do projeto e remove todos os arquivos .apk sem pedir confirmação.
# .DESCRIPTION
#     Procura recursivamente por arquivos com extensão .apk a partir da pasta atual
#     e os exclui permanentemente. CUIDADO: exclusão é irreversível.
#

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$apks = Get-ChildItem -Path $root -Recurse -Filter *.apk -File -ErrorAction SilentlyContinue

if ($apks.Count -eq 0) {
    Write-Host "Nenhum arquivo .apk encontrado em $root"
    exit 0
}

Write-Host "Encontrados $($apks.Count) arquivo(s) .apk:"
foreach ($apk in $apks) {
    Write-Host "  $apk.FullName  ($([math]::Round($apk.Length / 1MB, 2)) MB)"
}
Write-Host ""
Write-Host "Excluindo..."

$deleted = 0
foreach ($apk in $apks) {
    try {
        Remove-Item -Path $apk.FullName -Force -ErrorAction Stop
        Write-Host "  [OK] $($apk.FullName)"
        $deleted++
    } catch {
        Write-Host "  [FALHA] $($apk.FullName): $($_.Exception.Message)"
    }
}

Write-Host ""
Write-Host "===================================================="
Write-Host "  Total encontrados: $($apks.Count)"
Write-Host "  Total excluídos:   $deleted"
Write-Host "===================================================="
