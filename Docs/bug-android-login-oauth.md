# Bug: Login OAuth Android não redireciona de volta ao app

## Status
Aberto — 2026-08-04

## Sintoma
Ao clicar "Entrar com Google" no emulador (Pixel 8 API 34), o Custom Tab abre a tela de consentimento do Google normalmente. Após o usuário confirmar, o fluxo não retorna ao app. O emulador exibe a página `crossmeeting-web.vercel.app/auth/desktop-callback` (ou trava no browser), e o app permanece na tela de login sem autenticar.

## Causa raiz suspeita
O Chrome em Custom Tabs no Android bloqueia navegação para URIs de esquema customizado (`crossmeeting://`) quando esse redirect é iniciado por uma página web via `window.location.href`. Isso foi confirmado pela ausência de resposta mesmo com `intent://` URI format.

O Supabase Kotlin SDK (v3.0.0) constrói o `redirect_to` automaticamente a partir do `scheme`/`host` configurados no `SupabaseClient`. Tentativas de sobrescrever via `queryParams["redirect_to"]` podem ser ignoradas pelo SDK pois ele monta a URL internamente.

## O que já foi tentado

### 1. `queryParams["redirect_to"]` em `LoginScreen.kt`
```kotlin
queryParams["redirect_to"] = "https://crossmeeting-web.vercel.app/auth/desktop-callback"
```
Não funcionou — o SDK provavelmente ignora esse parâmetro ao construir a URL OAuth.

### 2. Página intermediária com `crossmeeting://` (`desktop-callback/page.tsx`)
```typescript
window.location.href = `crossmeeting://login-callback?code=${encodedCode}`
```
Não funcionou — Chrome Android bloqueia navegação para custom schemes em `window.location.href`.

### 3. Página intermediária com `intent://` URI
```typescript
window.location.href = `intent://login-callback?code=${encodedCode}#Intent;scheme=crossmeeting;package=ai.crossmeeting.app;end`
```
Não funcionou — ainda sem redirecionamento para o app.

## Configuração atual
- **`SupabaseClient.kt`**: `scheme = "crossmeeting"`, `host = "login-callback"` (correto para deep link)
- **`AndroidManifest.xml`**: intent-filter com `scheme="crossmeeting"` e `host="login-callback"` (correto)
- **`MainActivity.kt`**: `onNewIntent` chama `handleDeeplinks(intent)` (correto)
- **Supabase Dashboard**: `crossmeeting://login-callback` cadastrado como Redirect URL (correto)

## Próximos passos para investigar

### Opção A — Testar em dispositivo físico real
Custom Tabs em dispositivos físicos se comportam diferente do emulador. O problema pode ser exclusivo do emulador. Instalar o APK debug em um dispositivo real e testar.

### Opção B — Usar `openBrowser = true` no Supabase SDK
Forçar abertura no browser externo (fora do Custom Tab):
```kotlin
// No SupabaseClient.kt ou no signInWith config
// Verificar se o SDK v3 suporta esse flag
```

### Opção C — PKCE manual sem o SDK
Gerar a URL OAuth manualmente e controlar o redirect_to explicitamente:
```kotlin
val url = "https://gobnerbexyzktxhxuiju.supabase.co/auth/v1/authorize" +
    "?provider=google" +
    "&redirect_to=https://crossmeeting-web.vercel.app/auth/desktop-callback" +
    "&code_challenge=..." +
    "&code_challenge_method=S256"
```
Exige implementar PKCE code_verifier/challenge manualmente.

### Opção D — App Links (https:// deep links verificados)
Migrar de `crossmeeting://` para `https://crossmeeting-web.vercel.app` como deep link verificado (Android App Links). Chrome não bloqueia https://. Exige:
1. Criar `/.well-known/assetlinks.json` no crossmeeting-web
2. Mudar manifest para `android:autoVerify="true"` com scheme https
3. Mudar `SupabaseClient.kt` para `scheme = "https"`, `host = "crossmeeting-web.vercel.app"`

### Opção E — Verificar logs do emulador via Logcat
Abrir **Logcat** no Android Studio com filtro `CMAuth` ou `supabase` para ver se o deep link chega ao app e se o `handleDeeplinks` está sendo chamado.
