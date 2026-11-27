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

1. ✅ **AdMob:** app "BeatTrack" (`ca-app-pub-6552169828297838~7776540821`) e bloco **Banner rodape** (`…/2632025351`), no `release` do `app/build.gradle`. Falta **vincular à loja** (AdMob → Apps → BeatTrack → Adicionar loja) depois que o app estiver publicado; até lá a veiculação é limitada.
2. ✅ **Pagamentos AdMob:** a conta já existia com o perfil de pagamentos completo.
3. ✅ **Mensagens de privacidade (AdMob → Privacidade e mensagens):** "BeatTrack - GDPR" (botão "Não consentir" ativo, en + de/es/fr/it/nl/pl/pt-PT) e "BeatTrack - Estados dos EUA", publicadas.
4. ✅ **Política de privacidade:** https://netstart.github.io/beattrack/privacy-policy.html (repositório `netstart.github.io`, pasta `beattrack/`), informada na Play Console e no menu do app.
5. ✅ **Play Console → Conteúdo do app** (Detalhes do login: sem restrições; Público-alvo: 13+):
   - Anúncios: **Sim, contém anúncios**.
   - ID de publicidade: **usa** (finalidade: publicidade).
   - Segurança dos dados: o SDK do AdMob coleta **ID do dispositivo**, **interações com o app**, **diagnóstico** e **localização aproximada (IP)**; finalidade publicidade e análise; criptografados em trânsito; compartilhados com o Google.
6. **app-ads.txt:** exige um site do desenvolvedor na ficha da loja; opcional, mas aumenta a receita.

## Métricas para decidir a Fase 2

eCPM do banner, taxa de preenchimento, sessões/dia e quantas separações de stems por usuário.
