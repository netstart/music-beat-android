<#
.SYNOPSIS
    Compila o projeto BPM Player em modo RÁPIDO (desenvolvimento).
.DESCRIPTION
    Gera o APK debug sem otimizações de produção, pulando verificações lentas.
    Use para testes rápidos durante desenvolvimento.
    APK gerado em subpastas de: $PROJECT\app\build\outputs
#>

$JAVA_HOME = "C:\Java\jdk-17.0.20.1+1"
$GRADLE    = "C:\Gradle\gradle-8.2\bin\gradle.bat"
$PROJECT   = "C:\src\music-beat\bpm_app"

# Configura SDK para o Gradle
$env:ANDROID_HOME       = "C:\Android\Sdk"
$env:ANDROID_SDK_ROOT   = "C:\Android\Sdk"

# Marca início do script
$scriptStart = Get-Date

Write-Host "=== BUILD RÁPIDO (DESENVOLVIMENTO) ==="
Write-Host "JAVA_HOME: $JAVA_HOME"
Write-Host "Projeto:   $PROJECT"
Write-Host ""

# Verifica dependências
if (-not (Test-Path $JAVA_HOME)) {
    Write-Host "ERRO: JDK 17 não encontrado em $JAVA_HOME"
    Write-Host "  Baixe de: https://adoptium.net/temurin/releases/?version=17"
    Write-Host "  Ou aponte JAVA_HOME para o JDK do Android Studio:"
    Write-Host "    `$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'"
    exit 1
}
if (-not (Test-Path $GRADLE)) {
    Write-Host "ERRO: Gradle 8.2 não encontrado em $GRADLE"
    Write-Host "  Baixe: https://services.gradle.org/distributions/gradle-8.2-bin.zip"
    exit 1
}

# Configura ambiente para build rápido
$env:JAVA_HOME     = $JAVA_HOME
$env:GRADLE_OPTS   = "-Xmx2048m -XX:+UseParallelGC"

# Compila RÁPIDO (SEM MINIFICAÇÃO):
# --daemon                  : reutiliza daemon Gradle (mais rápido após 1ª execução)
# --parallel                : paraleliza tarefas independentes
# -PminifyEnabled=false     : garante que minify está desligado no build.gradle
# --daemon                  : reutiliza daemon Gradle (mais rápido após 1ª execução)
# --parallel                : paraleliza tarefas independentes
# -x lint                   : pula lint (lento, só para release)
# -x test                   : pula testes unitários
# -x check                  : pula verificações extras
# --offline                 : opcional - usa cache local sem checar rede (descomente se sem internet)
# --configure-on-demand     : configura só projetos necessários

$gradleArgs = @(
    "--daemon"
    "--parallel"
    "--configure-on-demand"
    "-x", "lint"
    "-x", "test"
    "-x", "check"
    "-p", $PROJECT
    "app:assembleDebug"
)

Write-Host "Executando: gradle $($gradleArgs -join ' ')"
& $GRADLE @gradleArgs
$exitCode = $LASTEXITCODE

# Verifica resultado - busca recursiva do APK em outputs
$apkFiles = Get-ChildItem -Path "$PROJECT\app\build\outputs" -Recurse -Filter *.apk -File | Sort-Object LastWriteTime -Descending
$APK_OUT = if ($apkFiles) { $apkFiles[0].FullName } else { $null }
if ($apkFiles) { Write-Host "APK encontrado: $APK_OUT" } else { Write-Host "AVISO: Nenhum .apk encontrado em $PROJECT\app\build\outputs" }
if ($exitCode -eq 0 -and $APK_OUT) {
    $sizeMB = [math]::Round((Get-Item $APK_OUT).Length / 1MB, 1)
    Write-Host ""
    Write-Host "===================================================="
    Write-Host "  BUILD RÁPIDO SUCESSO!"
    Write-Host "  APK: $APK_OUT ($sizeMB MB)"
    Write-Host "===================================================="

    # APK já está em subpastas de outputs; não precisa copiar para raiz
} else {
    $scriptEnd = Get-Date
    Write-Host ""
    Write-Host "FALHOU (saída do Gradle acima)"
    Write-Host ""
    Write-Host " Troubleshooting:"
    Write-Host "  * Verifique erros de compilação acima"
    Write-Host "  * Primeira execução é mais lenta (inicia daemon)"
    Write-Host "  * Se falhar, tente o build completo: .\run_to_prod_build_apk.ps1"
    Write-Host ""
    Write-Host "===================================================="
    Write-Host "  TEMPO TOTAL: $(($scriptEnd - $scriptStart).ToString('hh\:mm\:ss'))"
    Write-Host "===================================================="
    exit 1
}

# Mostra tempo total ao final (sucesso)
$scriptEnd = Get-Date
Write-Host ""
Write-Host "===================================================="
Write-Host "  TEMPO TOTAL: $(($scriptEnd - $scriptStart).ToString('hh\:mm\:ss'))"
Write-Host "===================================================="