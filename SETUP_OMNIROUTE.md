# Configuracao do OmniRoute no Cursor IDE

Este documento explica como configurar o OmniRoute como provedor de modelos de IA no Cursor.

## PROBLEMA: OmniRoute nao aparece no Cursor

Se voce seguiu os passos e a opcao "OmniRoute" nao aparece, a extensao nao esta instalada ou nao esta ativa.

---

## PASSO 1: INSTALAR A EXTENSAO OMNIROUTE (OBRIGATORIO)

A extensao "OmniRoute for Copilot Chat" precisa estar instalada no Cursor.

### Metodo A: Via Marketplace do Cursor (RECOMENDADO)

1. No Cursor, va em: **Extensions** (atalho: `Ctrl+Shift+X`)
2. Na barra de busca, digite: `OmniRoute for Copilot Chat`
3. Procure pela extensao de **diegosouzapw**
4. Clique em **Install**

### Metodo B: Via VSIX (Offline)

1. Baixe o .vsix de: https://open-vsx.org/extension/diegosouzapw/omnicopilot
2. No Cursor: `Ctrl+Shift+P` -> "Extensions: Install from VSIX..."
3. Selecione o arquivo .vsix baixado

### Metodo C: Via Linha de Comando

```bash
code --install-extension diegosouzapw.omnicopilot
```
Ou para Cursor:
```bash
cursor --install-extension diegosouzapw.omnicopilot
```

### Verificar instalacao

Apos instalar:
1. Recarregue o Cursor: `Ctrl+Shift+P` -> "Developer: Reload Window"
2. Veja se aparece um icone do OmniRoute na barra lateral (Activity Bar - esquerda)
3. Ou pressione `Ctrl+Shift+P` e digite `OmniRoute` - deve aparecer "OmniRoute: Manage Connection"

---

## PASSO 2: INICIAR O SERVIDOR OMNIROUTE

O servidor precisa estar rodando para a extensao funcionar.

### Verificar se ja esta rodando

Abra o terminal e execute:
```bash
curl http://localhost:20128/v1/models
```
- Se retornar JSON: o servidor ja esta rodando
- Se der erro: o servidor nao esta rodando

### Iniciar o servidor (se nao estiver)

```bash
# Se voce tem o omniroute instalado globalmente:
omniroute

# Se nao tem, instale primeiro:
npm install -g omniroute
omniroute
```

O servidor ficara disponivel em `http://localhost:20128`

---

## PASSO 3: CONFIGURAR A CONEXAO NO CURSOR

Apos a extensao estar instalada e o servidor rodando:

1. No Cursor, abra o **Command Palette**: `Ctrl+Shift+P`
2. Digite: `OmniRoute: Manage Connection`
3. **NAO APARECE?** Veja a secao de troubleshooting abaixo
4. Se aparecer, configure:
   - **Server URL**: `http://localhost:20128`
   - **API Key**: `sk-1268dbdaf87b8fe5-72e0f7-549ed8c4`
5. Clique em **"Save & Test"**
6. Deve aparecer mensagem de sucesso verde

---

## PASSO 4: ATIVAR OS MODELOS NO COPILOT CHAT

**IMPORTANTE:** O OmniRoute so aparecera no "Manage Models" APOS a conexao estar funcionando.

### 4.1: Primeiro, recarregue o Cursor

Apos configurar a conexao no PASSO 3:
1. `Ctrl+Shift+P` -> "Developer: Reload Window"
2. Aguarde o Cursor abrir novamente

### 4.2: Verificar se a conexao esta ativa

1. Veja se aparece um **ponto verde** ou **icone do OmniRoute** na barra de status (inferior direita)
2. Se aparecer **ponto vermelho** ou **erro**, volte ao PASSO 3 e verifique a API key

### 4.3: Abrir o Copilot Chat e Manage Models

1. Pressione `Ctrl+Shift+I` para abrir o Copilot Chat
2. **ANTES DE CLICAR no seletor de modelo**: verifique se ha um indicador de conexao
3. No topo do chat, clique no **seletor de modelo** (pode ser "GPT-4o" ou "Auto" ou similar)
4. Escolha **"Manage Models..."** ou **"Gerenciar Modelos..."**
5. **NAO APARECE OMNIROUTE?** Continue para a secao abaixo

### 4.4: Se OmniRoute NAO aparece no Manage Models

Isso significa que a extensao nao esta conectando. Tente:

**Opcao A: Verificar status da extensao**
1. `Ctrl+Shift+P` -> digite `OmniRoute: Check Connection`
2. Se mostrar erro, a API key nao foi salva corretamente
3. Repita o PASSO 3

**Opcao B: Verificar se ha erros**
1. `Ctrl+Shift+P` -> "Developer: Toggle Developer Tools"
2. Va na aba **Console**
3. Procure por erros com "omnicopilot" ou "omniroute"

**Opcao C: Reiniciar o processo**
1. `Ctrl+Shift+P` -> "Developer: Reload Window"
2. Aguarde 5 segundos
3. Repita o PASSO 3 novamente (mesmo que ja tenha feito)

### 4.5: Selecionar modelos (quando OmniRoute aparecer)

Quando a secao **OmniRoute** aparecer no Manage Models:
1. **EXPANDA** a secao clicando nela
2. Marque os modelos que deseja usar:
   - `auto/best-coding` (recomendado - melhor para programacao)
   - `auto/best-reasoning` (raciocinio)
   - `auto/best-fast` (rapido)
   - `auto/claude-opus` (modelo Claude Opus)
   - `auto/best-free` (gratuito)
   - `cl/nvidia/nemotron-3.5-lightning:free` (gratuito NVIDIA)
3. Clique **Done** ou **Salvar**

---

## PASSO 5: USAR O MODELO

1. No Copilot Chat, digite uma pergunta
2. O modelo selecionado sera usado
3. Para trocar de modelo: `@nome-do-modelo sua pergunta`
   - Exemplo: `@auto/best-coding faca um hello world em kotlin`

---

## TROUBLESHOOTING: OmniRoute NAO APARECE NO COMMAND PALETTE

### Verificar se a extensao esta ativa

1. `Ctrl+Shift+P` -> "Extensions: Show Installed Extensions"
2. Procure por "OmniRoute"
3. Se aparecer como "Disabled", clique em **Enable**

### Verificar erros da extensao

1. `Ctrl+Shift+P` -> "Developer: Toggle Developer Tools"
2. Va na aba **Console**
3. Procure por erros relacionados a "omnicopilot" ou "omniroute"

### Reinstalar a extensao

1. `Ctrl+Shift+P` -> "Extensions: Uninstall Extension"
2. Procure "OmniRoute" e desinstale
3. `Ctrl+Shift+P` -> "Developer: Reload Window"
4. Instale novamente via marketplace

### Verificar versao do Cursor

A extensao requer Cursor **versao 1.104+**. Verifique:
- `Ctrl+Shift+P` -> "About"
- Se a versao for antiga, atualize o Cursor

---

## VERIFICACAO FINAL

Execute estes comandos para confirmar que tudo esta funcionando:

```bash
# 1. Servidor rodando?
curl -s http://localhost:20128/v1/models | head -c 100

# 2. Quantidade de modelos?
# (Execute no browser: http://localhost:20128/v1/models)
# Deve mostrar {"object":"list","data":[...]} com 1600+ modelos

# 3. No Cursor:
# - Icone do OmniRoute visivel na barra lateral?
# - Ctrl+Shift+P -> "OmniRoute" mostra comandos?
# - Ctrl+Shift+I -> seletor de modelo mostra "OmniRoute"?
```

---

## CONFIGURACOES JA CRIADAS NESSE PROJETO

| Arquivo | O que faz |
|---------|-----------|
| `.vscode/settings.json` | Configura a URL, filtro de modelos e lista de modelos ativos |
| `.cursor/mcp.json` | Configura MCP server para integracao |
| `scripts/setup-omniroute-key.sh` | Script helper para configurar a chave |

---

## ARQUIVOS IMPORTANTES

**NAO COMMITAR** a chave API no git. O arquivo `.gitignore` deve incluir:
```
SETUP_OMNIROUTE.md
.vscode/settings.json  # se contiver apiKey
```

A chave atual: `sk-1268dbdaf87b8fe5-72e0f7-549ed8c4`
