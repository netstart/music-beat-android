<#
.SYNOPSIS
    Compila o projeto BPM Player para PRODUÇÃO (release) da forma mais rápida possível.
.DESCRIPTION
    Gera o APK release otimizado (minify, shrink, align) com flags de performance.
    APK gerado em: C:\src\music-beat\bpm_player-release.apk
#>

$JAVA_HOME = "C:\Java\jdk-17.0.20.1+1"
$GRADLE    = "C:\Gradle\gradle-8.2\bin\gradle.bat"
$PROJECT   = "C:\src\music-beat\bpm_app"
$OUT_DIR   = "C:\src\music-beat"

# Configura SDK para o Gradle
$env:ANDROID_HOME       = "C:\Android\Sdk"
$env:ANDROID_SDK_ROOT   = "C:\Android\Sdk"

# Marca início do script
$scriptStart = Get-Date

Write-Host "=== BUILD PRODUÇÃO (RELEASE) - MODO RÁPIDO ==="
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

# Configura ambiente para build release rápido
$env:JAVA_HOME     = $JAVA_HOME
$env:GRADLE_OPTS   = "-Xmx3072m -XX:+UseParallelGC -XX:ParallelGCThreads=4"

# Compila RELEASE otimizado e rápido:
# --daemon                  : reutiliza daemon Gradle
# --parallel                : paraleliza tarefas independentes
# --configure-on-demand     : configura só projetos necessários
# --max-workers=4           : limita workers (evita overhead de thread em máquinas com muitos cores)
# -p $PROJECT               : define projeto
# app:assembleRelease       : task de release (gera APK assinado/alinhado se signingConfig configurado)

$gradleArgs = @(
    "--daemon"
    "--parallel"
    "--configure-on-demand"
    "--max-workers=4"
    "-p", $PROJECT
    "app:assembleRelease"
)

Write-Host "Executando: gradle $($gradleArgs -join ' ')"
& $GRADLE @gradleArgs
$exitCode = $LASTEXITCODE

# Verifica resultado (APK release)
$APK_OUT = "$PROJECT\app\build\outputs\apk\release\app-release.apk"
# Fallback para unsigned se não assinado
$APK_UNSIGNED = "$PROJECT\app\build\outputs\apk\release\app-release-unsigned.apk"

if ($exitCode -eq 0) {
    $finalApk = if (Test-Path $APK_OUT) { $APK_OUT } elseif (Test-Path $APK_UNSIGNED) { $APK_UNSIGNED } else { $null }

    if ($finalApk) {
        $sizeMB = [math]::Round((Get-Item $finalApk).Length / 1MB, 1)
        Write-Host ""
        Write-Host "===================================================="
        Write-Host "  BUILD RELEASE SUCESSO!"
        Write-Host "  APK: $finalApk ($sizeMB MB)"
        Write-Host "===================================================="

        # Copia para a raiz do projeto
        Copy-Item $finalApk "$OUT_DIR\bpm_player-release.apk" -Force
        Write-Host "  Copiado para: $OUT_DIR\bpm_player-release.apk"
    } else {
        $scriptEnd = Get-Date
        Write-Host ""
        Write-Host "AVISO: Build passou mas APK não encontrado nos caminhos esperados"
        Write-Host "  Esperado: $APK_OUT ou $APK_UNSIGNED"
        Write-Host ""
        Write-Host "===================================================="
        Write-Host "  TEMPO TOTAL: $(($scriptEnd - $scriptStart).ToString('hh\:mm\:ss'))"
        Write-Host "===================================================="
        exit 1
    }
} else {
    $scriptEnd = Get-Date
    Write-Host ""
    Write-Host "FALHOU (saída do Gradle acima)"
    Write-Host ""
    Write-Host " Troubleshooting:"
    Write-Host "  * Verifique erros de compilação acima"
    Write-Host "  * Confira signingConfig no build.gradle (release precisa assinatura)"
    Write-Host "  * Para build debug rápido: .\run_build_low_apk.ps1"
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
