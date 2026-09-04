#
# .SYNOPSIS
#     Remove todos os arquivos .apk da pasta outputs e os logs da pasta logs.
# .DESCRIPTION
#     Procura recursivamente por arquivos com extensão .apk em bpm_app\app\build\outputs
#     e por todos os arquivos em logs\, e os exclui permanentemente. CUIDADO: exclusão é irreversível.
#

# === [1/2] Limpar APKs ===
$rootApk = "C:\src\music-beat\bpm_app\app\build\outputs"
$apks = Get-ChildItem -Path $rootApk -Recurse -Filter *.apk -File -ErrorAction SilentlyContinue

if ($apks.Count -eq 0) {
    Write-Host "Nenhum arquivo .apk encontrado em $rootApk"
} else {
    Write-Host "Encontrados $($apks.Count) arquivo(s) .apk:"
    foreach ($apk in $apks) {
        Write-Host "  $apk.FullName  ($([math]::Round($apk.Length / 1MB, 2)) MB)"
    }
    Write-Host ""
    Write-Host "Excluindo APKs..."

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
    Write-Host "  APKs encontrados: $($apks.Count)"
    Write-Host "  APKs excluídos:   $deleted"
}

Write-Host ""
# === [2/2] Limpar logs ===
$rootLogs = "C:\src\music-beat\logs"
$logs = Get-ChildItem -Path $rootLogs -File -ErrorAction SilentlyContinue

if ($logs.Count -eq 0) {
    Write-Host "Nenhum arquivo de log encontrado em $rootLogs"
} else {
    Write-Host "Encontrados $($logs.Count) arquivo(s) de log em $rootLogs :"
    foreach ($log in $logs) {
        Write-Host "  $log.FullName  ($([math]::Round($log.Length / 1KB, 1)) KB)"
    }
    Write-Host ""
    Write-Host "Excluindo logs..."

    $deletedLogs = 0
    foreach ($log in $logs) {
        try {
            Remove-Item -Path $log.FullName -Force -ErrorAction Stop
            Write-Host "  [OK] $($log.FullName)"
            $deletedLogs++
        } catch {
            Write-Host "  [FALHA] $($log.FullName): $($_.Exception.Message)"
        }
    }
    Write-Host ""
    Write-Host "  Logs encontrados: $($logs.Count)"
    Write-Host "  Logs excluídos:   $deletedLogs"
}

Write-Host ""
Write-Host "===================================================="
Write-Host "  Limpeza concluída."
Write-Host "===================================================="
