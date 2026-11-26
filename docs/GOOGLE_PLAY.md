# Google Play — publicação automática

Workflow: `.github/workflows/android.yml`

| Gatilho | Resultado |
|---|---|
| push na `main` | testes + AAB assinado → **teste fechado** (track `alpha`) |
| Actions → Android → **Run workflow** → `production` | AAB da `main` → **produção** |

- O `versionCode` é o número do run × 10, então cada build sobe sozinho.
- O AAB e o `mapping.txt` ficam como artifact de cada run.
- O ID do app é `io.github.netstart.beattrack`. O Play recusa `com.example.*`, e o ID não muda mais depois do 1º upload.

## Já configurado

- **Chave de upload:** fica em `%USERPROFILE%\.beattrack-upload\` (`upload.jks` + `LEIA-senha.txt`), fora do repositório. **Faça backup.** Com o Play App Signing ela pode ser trocada se for perdida.
- **Secrets no GitHub:** `UPLOAD_KEYSTORE_BASE64`, `UPLOAD_KEYSTORE_PASSWORD`, `UPLOAD_KEY_ALIAS` e `UPLOAD_KEY_PASSWORD`.

## Falta fazer (uma vez, na sua conta)

1. **Criar o app:** [Play Console](https://play.google.com/console) → *Criar app* → nome **BeatTrack**, app, gratuito. Aceite as declarações.
2. **Primeiro upload, manual:** a API não publica um app que nunca teve upload.
   1. Baixe o artifact `app-release-*` do último run verde em *Actions*.
   2. Vá em *Testar e lançar → Teste fechado → Alpha → Criar versão* e envie o `.aab`.
   3. Aceite o **Play App Signing**.
3. **Testadores:** no track Alpha, adicione uma lista de e-mails. Contas pessoais criadas depois de nov/2023 só liberam produção após **12 testadores ativos por 14 dias** no teste fechado.
4. **Preencher o conteúdo do app:** política de privacidade, classificação etiária, segurança dos dados, público-alvo e ficha da loja com ícone, capturas e descrições.
   - Segurança dos dados: o app não coleta nada. Ele só baixa o modelo de IA do Hugging Face.
5. **Criar a conta de serviço para a API:**
   1. No [Google Cloud](https://console.cloud.google.com), ative a **Google Play Android Developer API**.
   2. Crie uma conta de serviço e gere uma **chave JSON**.
   3. Na Play Console, vá em *Usuários e permissões → Convidar usuário* com o e-mail da conta de serviço. Dê permissão no app para *Lançar em tracks de teste* e *Lançar em produção*.
6. **Ligar o deploy no GitHub:**
   ```powershell
   gh secret set PLAY_SERVICE_ACCOUNT_JSON < caminho\da\chave.json
   ```
   ```powershell
   gh variable set PLAY_ENABLED --body true
   ```
   Até isso existir, o workflow só compila e guarda o AAB.

> Enquanto o app não passa na 1ª revisão do Google, rode o workflow com `status: draft`. Depois disso, `completed`.
