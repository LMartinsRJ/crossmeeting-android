# Crossmeeting Android — Instruções para Claude

## Regra principal: cirurgia, não reformas

**Só altere o que for estritamente necessário para resolver o problema pedido.**

Antes de editar qualquer arquivo:
1. Leia o arquivo inteiro
2. Identifique exatamente a linha que precisa mudar
3. Faça apenas aquela mudança
4. Não "melhorar" código ao redor, não renomear variáveis, não reorganizar imports além do necessário

Se a tarefa é corrigir um bug em `ActionsScreen.kt`, não toque em `MeetingDetailScreen.kt` a não ser que seja diretamente necessário.

## O que NÃO fazer sem pedido explícito

- Não adicionar tratamento de erro onde não havia
- Não extrair funções ou criar helpers "para reutilização"
- Não mudar visibilidade de funções (`private` → `internal`) além do necessário
- Não refatorar estrutura de composables existentes
- Não adicionar imports além dos necessários para a mudança
- Não alterar lógica de negócio enquanto corrige compilação

## Antes de cada mudança, pergunte

- Essa alteração é necessária para o que foi pedido?
- Existe risco de quebrar algo que já funciona?
- Estou tocando em mais de um arquivo? Por quê?

## Arquivos de alto risco (mexer com cuidado)

| Arquivo | Risco |
|---|---|
| `MainActivity.kt` | Controla ciclo de vida, OAuth deep link, sessão |
| `SupabaseClient.kt` | Credenciais e configuração PKCE — errar quebra login |
| `RecordingService.kt` | Serviço em background — crash derruba gravação |
| `AndroidManifest.xml` | Intent filters, permissões — errar impede deep link OAuth |
| `local.properties` | Credenciais — verificar projeto Supabase antes de alterar |

## Stack

- Kotlin + Jetpack Compose (Material3)
- Supabase Kotlin SDK 3.0.0 (Auth com PKCE: `scheme="crossmeeting"`, `host="login-callback"`)
- Ktor para WebSocket (Deepgram)
- Gradle KTS

## OAuth Android — não alterar sem entender o fluxo completo

O fluxo PKCE depende de:
1. `scheme = "crossmeeting"`, `host = "login-callback"` em `SupabaseClient.kt` (gera `code_challenge`)
2. `crossmeeting://login-callback` no AndroidManifest com `launchMode="singleTask"`
3. `onNewIntent` em `MainActivity.kt` chamando `handleDeeplinks`
4. `SUPABASE_ANON_KEY` em `local.properties` deve ser do mesmo projeto que `SUPABASE_URL`

Alterar qualquer um desses pontos sem entender os outros quebra o login.
