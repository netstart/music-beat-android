#!/bin/bash
# Captura log do emulador + logcat para diagnosticar travamento

LOG_DIR="C:/src/music-beat/logs"
mkdir -p "$LOG_DIR"

EMULATOR_LOG="$LOG_DIR/emulator.log"
LOGCAT_LOG="$LOG_DIR/logcat.log"
LOGCAT_ERR="$LOG_DIR/logcat_errors.log"
ANR_LOG="$LOG_DIR/anr.log"
TOMB_LOG="$LOG_DIR/tombstones.log"
CRASH_LOG="$LOG_DIR/crash_dropbox.log"

echo "==================================================="
echo "  Captura de Log - Emulador + App"
echo "  Logs em: $LOG_DIR"
echo "==================================================="

# 1. Mata processos anteriores
echo "[1/6] Encerrando emuladores anteriores..."
taskkill //F //IM emulator.exe 2>/dev/null
taskkill //F //IM qemu-system-x86_64.exe 2>/dev/null
taskkill //F //IM adb.exe 2>/dev/null
sleep 3

# 2. Limpa logs antigos
echo "[2/6] Limpando logs antigos..."
true > "$EMULATOR_LOG"
true > "$LOGCAT_LOG"
true > "$LOGCAT_ERR"
true > "$ANR_LOG"
true > "$TOMB_LOG"
true > "$CRASH_LOG"

# 3. Inicia emulador com log verbose
echo "[3/6] Iniciando emulador (verbose)..."
/c/Android/Sdk/emulator/emulator.exe -avd test_device -accel on -gpu host -no-snapshot-load -verbose > "$EMULATOR_LOG" 2>&1 &
EMUL_PID=$!
echo "  Emulator PID: $EMUL_PID"
sleep 5

# 4. Aguarda boot
echo "[4/6] Aguardando boot..."
ADB="/c/Android/Sdk/platform-tools/adb.exe"
ready=false
for i in $(seq 1 90); do
    boot=$($ADB -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n ')
    if [ "$boot" = "1" ]; then
        ready=true
        break
    fi
    if [ $((i % 10)) -eq 0 ]; then
        echo "  ...boot tentativa $i/90"
    fi
    sleep 2
done

if [ "$ready" = "false" ]; then
    echo "  ERRO: Boot nao completou"
    echo "  Ultimas linhas do log:"
    tail -20 "$EMULATOR_LOG"
    exit 1
fi
echo "  Android pronto"

# Limpa logcat e inicia captura em background
$ADB -s emulator-5554 logcat -c
echo "  Iniciando captura de logcat..."
$ADB -s emulator-5554 logcat -v time > "$LOGCAT_LOG" 2>&1 &
LOGCAT_PID=$!
echo "  Logcat PID: $LOGCAT_PID"

# 5. Instala o APK
echo "[5/6] Instalando e abrindo o APK..."
APK="C:/src/music-beat/bpm_app/app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK" ]; then
    $ADB -s emulator-5554 install -r "$APK" 2>&1 | head -3
else
    echo "  ERRO: APK nao encontrado em $APK"
    kill $LOGCAT_PID 2>/dev/null
    exit 1
fi

# Limpa dados ANR/tombstones anteriores
$ADB -s emulator-5554 shell "rm -rf /data/tombstones/*" 2>/dev/null
$ADB -s emulator-5554 shell "rm -f /data/anr/*" 2>/dev/null

# Abre o app
$ADB -s emulator-5554 shell am start -n com.example.bpm_player/.MainActivity 2>&1 | head -3
echo "  App aberto"

# 6. Monitora por 60 segundos
echo "[6/6] Monitorando app por 60s..."
app_pid=""
was_alive=false
for i in $(seq 1 60); do
    pid=$($ADB -s emulator-5554 shell pidof com.example.bpm_player 2>/dev/null | tr -d '\r\n ')
    if [ -n "$pid" ]; then
        if [ -z "$app_pid" ]; then
            app_pid=$pid
            echo "  [${i}s] App INICIOU: PID $pid"
        fi
        was_alive=true
        # A cada 5s, verifica se ainda vivo
        if [ $((i % 10)) -eq 0 ]; then
            echo "  [${i}s] App vivo: PID $pid"
        fi
    else
        if [ "$was_alive" = "true" ]; then
            echo "  [${i}s] *** APP MORREU! (era PID $app_pid) ***"
            was_alive=false
            break
        elif [ -z "$app_pid" ]; then
            if [ $((i % 10)) -eq 0 ]; then
                echo "  [${i}s] Aguardando app subir..."
            fi
        fi
    fi
    sleep 1
done

# Coleta informacoes do crash
echo ""
echo "==================================================="
echo "  COLETANDO INFORMACOES DE CRASH"
echo "==================================================="

# ANR
echo "--- ANR (/data/anr/) ---"
$ADB -s emulator-5554 shell "ls -la /data/anr/" 2>&1 >> "$ANR_LOG"
$ADB -s emulator-5554 shell "cat /data/anr/*.txt 2>/dev/null" >> "$ANR_LOG" 2>&1

# Tombstones
echo "--- Tombstones ---"
$ADB -s emulator-5554 shell "ls -la /data/tombstones/" 2>&1 >> "$TOMB_LOG"
$ADB -s emulator-5554 shell "cat /data/tombstones/* 2>/dev/null" >> "$TOMB_LOG" 2>&1

# logcat buffer de crash
echo "--- Logcat buffer crash ---"
$ADB -s emulator-5554 logcat -d -b crash -v time 2>&1 >> "$CRASH_LOG"

# Erros do logcat
$ADB -s emulator-5554 logcat -d -v time *:E 2>&1 > "$LOGCAT_ERR"

# Dropbox (historico de crashes)
$ADB -s emulator-5554 shell dumpsys dropbox --print 2>&1 | tail -100 >> "$CRASH_LOG"

# Dump da pilha do app
$ADB -s emulator-5554 shell "ps -A" 2>&1 | grep bpm_player >> "$CRASH_LOG"

# Para captura de logcat
kill $LOGCAT_PID 2>/dev/null

# Mostra resumo
echo ""
echo "=== ERROS DO LOGCAT (ultimas 30 linhas) ==="
tail -30 "$LOGCAT_ERR"
echo ""
echo "=== FATAL EXCEPTION ==="
grep -A 30 "FATAL EXCEPTION" "$LOGCAT_LOG" 2>/dev/null | head -50
echo ""
echo "=== ANR (se houver) ==="
[ -s "$ANR_LOG" ] && head -40 "$ANR_LOG" || echo "(vazio)"
echo ""
echo "=== TOMBSTONES (se houver) ==="
[ -s "$TOMB_LOG" ] && head -40 "$TOMB_LOG" || echo "(vazio)"
echo ""
echo "=== CRASH DROPPED ==="
grep -A 5 "process com.example.bpm_player" "$CRASH_LOG" 2>/dev/null | head -30
echo ""
echo "==================================================="
echo "  Logs salvos em: $LOG_DIR"
echo "  Emulator log:  $EMULATOR_LOG ($(wc -l < "$EMULATOR_LOG") linhas)"
echo "  Logcat full:   $LOGCAT_LOG ($(wc -l < "$LOGCAT_LOG") linhas)"
echo "  Logcat errors: $LOGCAT_ERR"
echo "  ANR:           $ANR_LOG"
echo "  Tombstones:    $TOMB_LOG"
echo "  Crash:         $CRASH_LOG"
echo "==================================================="

# Para emulador
taskkill //F //IM emulator.exe 2>/dev/null
taskkill //F //IM qemu-system-x86_64.exe 2>/dev/null
taskkill //F //IM adb.exe 2>/dev/null
echo "Emulador encerrado."
