#!/bin/bash
# Script para configurar a API Key do OmniRoute no Cursor
# Execute: bash scripts/setup-omniroute-key.sh

API_KEY="sk-1268dbdaf87b8fe5-72e0f7-549ed8c4"
SETTINGS_FILE="$HOME/AppData/Roaming/Cursor/User/settings.json"

echo "Configurando API Key do OmniRoute no Cursor..."

# Backup
cp "$SETTINGS_FILE" "$SETTINGS_FILE.backup.$(date +%s)"

# Usa jq para atualizar o JSON
jq --arg key "$API_KEY" '. + {"omnicopilot.apiKey": $key}' "$SETTINGS_FILE" > "$SETTINGS_FILE.tmp" && mv "$SETTINGS_FILE.tmp" "$SETTINGS_FILE"

echo "✅ API Key configurada com sucesso!"
echo "Reinicie o Cursor para aplicar as mudanças."