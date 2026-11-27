# Monetização com anúncios — plano

> Status: **Fase 1 implementada** (banner + consentimento). Atualizado em 2026-11-27.

## Decisão

| Formato | Usar? | Por quê |
|---|---|---|
| **Banner adaptativo fixo** acima do rodapé | ✅ Fase 1 | Não interrompe a música; receita contínua enquanto o app fica aberto (uso típico: treino/ensaio com a tela ligada) |
| **Recompensado** ("assista e separe os elementos") | ⏳ Fase 2 | A separação de stems é o recurso mais valioso e caro; troca justa e opt-in. Só depois de medir uso real |
| **Intersticial** | ❌ | Interromperia a reprodução — pior avaliação e retenção num player |
| **App open** | ❌ | Atrasa o uso imediato do player |
| **Remover anúncios (compra única)** | ⏳ Fase 3 | Exige Play Billing; vale quando houver usuários pagantes em potencial |

Rede: **Google AdMob** (SDK `play-services-ads`) + **UMP** (`user-messaging-platform`) para consentimento (GDPR/EEA, estados dos EUA).

## Como está no app

- `Ads.kt`: pede consentimento (UMP) → inicializa o AdMob em segundo plano → carrega o banner em `adContainer`.
- O banner só aparece depois de carregado; sem rede ou sem consentimento, a área some.
- Menu da toolbar mostra **Privacidade dos anúncios** quando o UMP exige o ponto de entrada.
- IDs em `app/build.gradle`: o debug usa sempre os **IDs de teste** do Google; o release usa os IDs reais do AdMob.

## Configuração externa (uma vez)

1. **AdMob:** conta, app "BeatTrack" (Android, vinculado ao Play depois da publicação), bloco de anúncio **Banner**.
2. **Pagamentos AdMob:** dados fiscais e bancários — **só o titular preenche**.
3. **Mensagens de privacidade (AdMob → Privacidade e mensagens):** criar a mensagem GDPR e a de estados dos EUA.
4. **Política de privacidade** pública (`docs/privacy.html` via GitHub Pages) informada no Play e no AdMob.
5. **Play Console → Conteúdo do app:**
   - Anúncios: **Sim, contém anúncios**.
   - ID de publicidade: **usa** (finalidade: publicidade).
   - Segurança dos dados: o SDK do AdMob coleta **ID do dispositivo**, **interações com o app**, **diagnóstico** e **localização aproximada (IP)**; finalidade publicidade e análise; criptografados em trânsito; compartilhados com o Google.
6. **app-ads.txt:** exige um site do desenvolvedor na ficha da loja; opcional, mas aumenta a receita.

## Métricas para decidir a Fase 2

eCPM do banner, taxa de preenchimento, sessões/dia e quantas separações de stems por usuário.
