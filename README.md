# BPM Player

Aplicativo Android que reproduz arquivos de áudio (MP3) com controle de BPM em tempo real — sem usar NDK ou C++. Tudo em Kotlin puro, usando o time-stretch nativo do ExoPlayer (Sonic).

## Pré-requisitos

| Ferramenta | Caminho/Instalação |
|---|---|
| Android SDK (API 34+) | `C:\Android\Sdk` |
| cmdline-tools | `C:\Android\Sdk\cmdline-tools\latest` |
| Emulator | `C:\Android\Sdk\emulator` |
| System Image (Android 14) | `system-images;android-34;google_apis;x86_64` |
| Gradle 8.2 | `C:\Gradle\gradle-8.2` |
| JDK 17+ (recomendado) | `C:\Java\jdk-17.0.20.1+1` ou Java 20 |
| Git | (opcional, para clonar) |

## Estrutura do Projeto

```
music-beat/
└── bpm_app/                 # Projeto Android
    ├── build.gradle         # Root build
    ├── settings.gradle      # Inclui :app
    └── app/
        ├── build.gradle     # Dependências (Media3, Material, Kotlin)
        └── src/main/
            ├── AndroidManifest.xml
            ├── kotlin/com/example/bpm_player/
            │   ├── MainActivity.kt
            │   ├── BpmDetector.kt      # Detecção de BPM (autocorrelação)
            │   └── AudioDecoder.kt    # MediaExtractor → PCM
            └── res/
                └── layout/activity_main.xml
```

## Primeira Configuração (SDK)

O SDK precisa ter instalado:
- `platforms;android-34`
- `build-tools;34.0.0`
- `platform-tools`
- `emulator`
- `system-images;android-34;google_apis;x86_64`

Para instalar/verificar:

```powershell
& "C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat" "platforms;android-34" "build-tools;34.0.0" "platform-tools" "emulator" "system-images;android-34;google_apis;x86_64"
```

Para criar o AVD (dispositivo virtual):

```powershell
"C:\Android\Sdk\cmdline-tools\latest\bin\avdmanager.bat" create avd -n test_device -k "system-images;android-34;google_apis;x86_64" -d "pixel_7"
```

Para aumentar a RAM do emulador, edite:

```
C:\Users\<SEU_USUARIO>\.android\avd\test_device.avd\config.ini
```

Altere:

```
hw.ramSize = 6144    # 6 GB
hw.gpu.mode = host
```

## À Mão — Comandos Diretos

### 1. Subir o emulador

```powershell
Start-Process "C:\Android\Sdk\emulator\emulator.exe" -ArgumentList "-avd test_device -accel on -gpu host -memory 6144 -no-snapshot-load"
```

### 2. Esperar o boot

```powershell
C:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 wait-for-device
    
# Confirma que terminou
C:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 shell getprop sys.boot_completed
# Retorna "1" quando pronto
```

### 3. Enviar MP3s para o emulador

```powershell
# Copia para a pasta padrão de música
C:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 push "C:\Users\piru\Music\Deixa-Me Ir - 7AL3M.mp3" /sdcard/Music/

# Indexa o arquivo para aparecer no seletor
C:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/
```

### 4. Instalar e abrir o app

```powershell
C:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 install -r "C:\src\music-beat\bpm_app\app\build\outputs\apk\debug\app-debug.apk"
C:\Android\Sdk\platform-tools\adb.exe -s emulator-5554 shell am start -n "com.example.bpm_player/.MainActivity"
```

### 5. Testar

1. No emulador, toque em **"Escolher música"**
2. Menu lateral → **Music**
3. Selecione o arquivo MP3
4. Arraste o **slider** para alterar o BPM em tempo real

---

## Script Automatizado

O projeto inclui `run_emulador.ps1` — basta rodar:

```powershell
cd C:\src\music-beat
.\run_emulador.ps1
```

Ele faz todos os passos acima automaticamente (inicia, espera, copia MP3s, instala e abre).

---

## Troubleshooting

| Problema | Causa | Solução |
|---|---|---|
| Emulador travando/lento | Aceleração desabilitada | Use `-accel on` e confirme WHPX (`emulator -accel-check`) |
| "Transport endpoint is not connected" | SD card não montado | Espere mais ou recrie o AVD (`avdmanager delete avd -n test_device`) |
| APK não instala | Boot incompleto | Espere `sys.boot_completed == 1` |
| MP3 não aparece | MediaStore não indexou | Force scan: `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/arquivo.mp3` |
| Erro de permissão ao selecionar (API 33+) | `READ_MEDIA_AUDIO` nu permission | O app pede em runtime; conceda manualmente em Configurações se falhar |
| Áudio engasgando/echo | Emulador software | Roda em celular físico ou use `-no-audio` para UI apenas |

## Aprendendo Mais

- Detectação de BPM: `BpmDetector.kt` — usa autocorrelação de envelope de energia
- Decodificação MP3: `AudioDecoder.kt` — MediaExtractor + MediaCodec
- Tempo real: `setPlaybackSpeed()` do ExoPlayer aplica instantaneamente (sem pausar)
