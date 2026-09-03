<#
.SYNOPSIS
    Compila o projeto BPM Player e gera o APK na pasta output.
.DESCRIPTION
    Gera o APK e copia para C:\src\music-beat\app-debug.apk
#>

$JAVA_HOME = "C:\Java\jdk-17.0.20.1+1"
$GRADLE    = "C:\Gradle\gradle-8.2\bin\gradle.bat"
$PROJECT   = "C:\src\music-beat\bpm_app"
$OUT_DIR   = "C:\src\music-beat"

# Configura SDK para o Gradle
$env:ANDROID_HOME       = "C:\Android\Sdk"
$env:ANDROID_SDK_ROOT = "C:\Android\Sdk"

Write-Host "Compilando BPM Player APK..."
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

# Configura ambiente
$env:JAVA_HOME     = $JAVA_HOME
$env:GRADLE_OPTS   = "-Xmx1024m"

# Compila
& $GRADLE --no-daemon --max-workers=2 -p $PROJECT app:assembleDebug
$exitCode = $LASTEXITCODE

# Verifica resultado
$APK_OUT = "$PROJECT\app\build\outputs\apk\debug\app-debug.apk"
if ($exitCode -eq 0 -and (Test-Path $APK_OUT)) {
    $sizeMB = [math]::Round((Get-Item $APK_OUT).Length / 1MB, 1)
    Write-Host ""
    Write-Host "===================================================="
    Write-Host "  BUILD SUCESSO!"
    Write-Host "  APK: $APK_OUT ($sizeMB MB)"
    Write-Host "===================================================="

    # Copia para a raiz do projeto
    Copy-Item $APK_OUT "$OUT_DIR\bpm_player.apk" -Force
    Write-Host "  Copiado para: $OUT_DIR\bpm_player.apk"
} else {
    Write-Host ""
    Write-Host "FALHOU (saída do Gradle acima)"
    Write-Host ""
    Write-Host " Troubleshooting:"
    Write-Host "  * Verifique se há erros de compilacao acima"
    Write-Host "  * Se a Internet estiver lenta/offline, o Gradle vai reutilizar o cache"
    exit 1
}
