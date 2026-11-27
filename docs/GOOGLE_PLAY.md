# Google Play — publicação automática

Workflow: `.github/workflows/android.yml`

| Gatilho | Resultado |
|---|---|
| push na `main` | testes + AAB assinado → **teste fechado** (track `alpha`) |
| Actions → Android → **Run workflow** → `production` | AAB da `main` → **produção** |

- O `versionCode` é o número do run × 10, então cada build sobe sozinho.
- O AAB e o `mapping.txt` ficam como artifact de cada run.
- O ID do app é `io.github.netstart.beattrack` (o Play recusa `com.example.*`).
- O `minSdk` é 24, exigido pelo ONNX Runtime e pela proteção automática do Play.

## Configurado

- **Play Console:** app **BeatTrack** criado como gratuito.
- **Conta de serviço:** `beattrack-play-publisher@gen-lang-client-0110100183.iam.gserviceaccount.com`. Tem acesso só ao BeatTrack, com permissão para lançar em produção e em teste e editar o rascunho.
- **Arquivos locais:** ficam em `%USERPROFILE%\.beattrack-upload\` — a chave de upload (`upload.jks` + `LEIA-senha.txt`) e a chave JSON da conta de serviço. **Faça backup.**
- **Secrets no GitHub:** `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS`, `UPLOAD_KEY_PASSWORD` e `PLAY_SERVICE_ACCOUNT_JSON`.
- **Variáveis no GitHub:**
  - `PLAY_ENABLED=true` liga o deploy.
  - `PLAY_STATUS=draft`: o app ainda está em rascunho no Play, que só aceita versões `draft` por enquanto.

## Falta (só na Play Console, uma vez)

1. **Conteúdo do app** (*Política → Conteúdo do app*): política de privacidade, classificação etária, segurança dos dados (o app não coleta nada), público-alvo e anúncios (não tem).
2. **Ficha da loja:** ícone 512 px, imagem de destaque 1024×500, pelo menos 2 capturas de tela e as descrições.
3. **Testadores:**
   1. Em *Teste fechado → Alpha*, adicione uma lista de e-mails e os países.
   2. Envie a versão em rascunho para revisão.
4. **Depois que o Google aprovar:**
   ```powershell
   gh variable delete PLAY_STATUS
   ```
   A partir daí cada push publica direto.
5. **Produção:** contas pessoais novas precisam de **12 testadores ativos por 14 dias** antes. Depois solicite o acesso de produção na Play Console e rode o workflow com `production`.
