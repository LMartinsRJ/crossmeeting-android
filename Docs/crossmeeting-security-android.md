# Crossmeeting Android — Análise de Segurança

**Data:** 2026-07-28  
**Revisado por:** Claude Code (análise estática automatizada)  
**Escopo:** App Android Kotlin + Jetpack Compose + Supabase  
**Branch:** master  

---

## Resumo Executivo

O app Crossmeeting Android apresenta uma postura de segurança geral **razoável para um MVP em fase inicial**, com várias boas práticas adotadas por padrão: chave anon do Supabase sem a service role key, cleartext desabilitado no manifest, tokens Deepgram efêmeros via Edge Function, backup de SharedPreferences desabilitado e `RecordingService` não exportado.

No entanto, foram identificados **2 achados críticos**, **3 altos** e **7 de severidade média/baixa** que precisam ser endereçados antes de uma distribuição pública.

O achado mais grave é a **ausência da permissão `FOREGROUND_SERVICE_MEDIA_PROJECTION` no manifest** (causa `SecurityException` em Android 14+ quando o usuário tenta usar captura de áudio de playback), seguido pelo **deep link OAuth sem `autoVerify`**, que expõe o flow de login a hijacking por apps maliciosos instalados no mesmo dispositivo.

---

## Tabela de Achados

| ID | Severidade | Categoria | Título | Arquivo:Linha |
|----|-----------|-----------|--------|---------------|
| SEC-01 | **Crítico** | Permissões / Foreground Service | `FOREGROUND_SERVICE_MEDIA_PROJECTION` ausente no manifest | `AndroidManifest.xml:40` / `RecordingService.kt:400` |
| SEC-02 | **Crítico** | Deep links / Intent handling | Deep link OAuth sem `autoVerify` — hijackable por terceiros | `AndroidManifest.xml:29` |
| SEC-03 | **Alto** | Comunicação de rede | `ConnectionSpec.CLEARTEXT` habilitado no cliente WebSocket | `RecordingService.kt:141` |
| SEC-04 | **Alto** | ProGuard / R8 | Regras ProGuard mantêm app inteiro sem ofuscação | `proguard-rules.pro:25` |
| SEC-05 | **Alto** | Dependências | Ktor em versão Release Candidate em produção | `app/build.gradle.kts:74-76` |
| SEC-06 | **Médio** | Logs e depuração | `Log.d` verboso em builds de release (frames, conteúdo de reunião) | `RecordingService.kt:243` / `RecordingScreen.kt:122` |
| SEC-07 | **Médio** | Autenticação | Falha silenciosa no `refreshCurrentSession()` sem tratamento | `MainActivity.kt:76-79` |
| SEC-08 | **Médio** | Widget | `MeetingWidgetReceiver` exported sem `android:permission` — DDoS de queries | `AndroidManifest.xml:43` |
| SEC-09 | **Médio** | Foreground Service | Notificação com `VISIBILITY_PUBLIC` — aparece na lock screen | `RecordingService.kt:376` |
| SEC-10 | **Baixo** | ProGuard / R8 | Supabase, Ktor e OkHttp mantidos sem ofuscação | `proguard-rules.pro:14-22` |
| SEC-11 | **Baixo** | Dependências | Compose BOM desatualizada (`2024.09.00`) | `app/build.gradle.kts:54` |
| SEC-12 | **Informativo** | Armazenamento de credenciais | `SUPABASE_ANON_KEY` embutida no APK via BuildConfig | `SupabaseClient.kt:15` |
| SEC-13 | **Informativo** | Injeção / Prompt injection | Conteúdo de transcrição exibido sem sanitização HTML | `MeetingDetailScreen.kt:285` |

---

## Seção Detalhada dos Achados

---

### SEC-01 — Crítico | `FOREGROUND_SERVICE_MEDIA_PROJECTION` ausente no manifest

**Descrição:**  
O `RecordingService` declara `foregroundServiceType="microphone"` no `AndroidManifest.xml` (linha 40), mas no código (linha 400-402) calcula o tipo como `FOREGROUND_SERVICE_TYPE_MICROPHONE or FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` quando `hasPlayback == true`. Para usar `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`, o manifest deve declarar explicitamente:

```xml
android:foregroundServiceType="microphone|mediaProjection"
```

**Localização:**
- `AndroidManifest.xml:40` — `android:foregroundServiceType="microphone"` (faltando `|mediaProjection`)
- `RecordingService.kt:400-403` — cálculo do `type` com `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`

**Impacto:**  
Em Android 14+ (API 34+), iniciar um `ForegroundService` com um tipo não declarado no manifest lança `SecurityException` em runtime, crashando o serviço. O usuário veria a gravação falhar silenciosamente (sem captura de áudio de playback) ou o app trava quando `hasPlayback=true`. Como `minSdk=26` e o projeto visa usuários corporativos com dispositivos modernos, grande parte da base de usuários seria afetada.

**Recomendação:**

```xml
<!-- AndroidManifest.xml -->
<service
    android:name=".recording.RecordingService"
    android:exported="false"
    android:foregroundServiceType="microphone|mediaProjection" />
```

Também adicionar a permissão ao manifest:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
```

---

### SEC-02 — Crítico | Deep link OAuth sem `autoVerify` — hijackable por terceiros

**Descrição:**  
O `intent-filter` para o callback de login OAuth usa o scheme customizado `crossmeeting://` com `android:autoVerify="false"` explícito:

```xml
<!-- AndroidManifest.xml:29 -->
<intent-filter android:autoVerify="false">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="crossmeeting" android:host="login-callback" />
</intent-filter>
```

Schemes customizados (`crossmeeting://`) não são verificáveis via Digital Asset Links (DAL), ao contrário de HTTPS App Links. Qualquer app malicioso instalado no dispositivo pode registrar o mesmo scheme e ser selecionado pelo Android para receber o intent de retorno do OAuth — o usuário veria um seletor ("Abrir com: Crossmeeting / App Malicioso") ou, se o app malicioso for o único registrado, recebe o callback diretamente.

**PoC:**  
Um app adversário declara no seu manifest:
```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="crossmeeting" android:host="login-callback" />
</intent-filter>
```
Quando o Google/Microsoft redireciona para `crossmeeting://login-callback?code=OAUTH_CODE`, o Android entrega ao app adversário que captura o `code` e pode trocar por token de acesso.

**Impacto:**  
Roubo de OAuth authorization code → acesso à conta do usuário.

**Recomendação:**  
Migrar para HTTPS App Links:
1. Registrar o deep link como `https://crossmeeting.app/auth/callback` (domínio próprio)
2. Hospedar o Digital Asset Links JSON em `https://crossmeeting.app/.well-known/assetlinks.json`
3. Usar `android:autoVerify="true"` no intent-filter com scheme `https`

Como alternativa de curto prazo, adicionar validação do `intent.data` no `onNewIntent`:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val uri = intent.data
    if (uri?.scheme == "crossmeeting" && uri.host == "login-callback") {
        // Verificar se o intent vem do sistema (package == null para intents do sistema)
        SupabaseClientProvider.client.handleDeeplinks(intent)
    }
}
```

---

### SEC-03 — Alto | `ConnectionSpec.CLEARTEXT` habilitado no cliente WebSocket

**Descrição:**  
Em `RecordingService.kt:138-144`, o `HttpClient` OkHttp é configurado com:

```kotlin
config {
    readTimeout(0, TimeUnit.MILLISECONDS)
    connectionSpecs(listOf(tlsSpec, ConnectionSpec.CLEARTEXT))
}
```

`ConnectionSpec.CLEARTEXT` é explicitamente adicionado à lista de specs, habilitando conexões HTTP sem TLS para esse cliente. Embora o `AndroidManifest.xml` declare `android:usesCleartextTraffic="false"` no nível do app, o `connectionSpecs()` do OkHttp opera em camada diferente e pode sobrepor a Network Security Config em algumas versões/implementações do OkHttp.

**Localização:** `RecordingService.kt:141`

**Impacto:**  
Se o fallback para cleartext ocorrer (por bug, versão do OkHttp ou configuração do dispositivo), o áudio PCM da reunião e o token efêmero do Deepgram seriam transmitidos em texto plano. Em redes corporativas com proxies MiTM, isso exporia conteúdo sensível de reuniões.

**Recomendação:**  
Remover `ConnectionSpec.CLEARTEXT` da lista:

```kotlin
engine {
    config {
        readTimeout(0, TimeUnit.MILLISECONDS)
        connectionSpecs(listOf(
            ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
                .build()
        ))
    }
}
```

---

### SEC-04 — Alto | Regras ProGuard mantêm app inteiro sem ofuscação

**Descrição:**  
O `proguard-rules.pro` linha 25 contém:

```
-keep class ai.crossmeeting.app.** { *; }
```

Essa regra mantém **todos** os nomes de classe, método e campo do pacote `ai.crossmeeting.app` sem qualquer ofuscação. Embora `isMinifyEnabled = true` (remoção de código morto via R8 está ativa), a ofuscação de nomes — que é a parte que dificulta engenharia reversa — fica desabilitada para todo o código da aplicação.

**Localização:** `proguard-rules.pro:25`

**Impacto:**  
Um atacante que obtenha o APK pode fazer engenharia reversa trivial: nomes de métodos como `stopAndSave`, `handleDeepgramMessage`, `refreshCurrentSession`, nomes de campos como `finalTranscript`, `providerRefreshToken` ficam legíveis em plaintext no bytecode. Facilita análise de vulnerabilidades e bypass de lógica do app.

**Recomendação:**  
Substituir a regra genérica por keep rules específicas apenas para as classes que efetivamente precisam de reflexão (os data classes usados pelo kotlinx.serialization já são cobertos pela regra `@kotlinx.serialization.Serializable`):

```proguard
# Manter apenas o que kotlinx.serialization realmente precisa via reflexão
# Os @Serializable data classes já são cobertos pela regra acima.
# Remover a linha abaixo:
# -keep class ai.crossmeeting.app.** { *; }

# Manter apenas componentes Android que o sistema instancia por nome
-keep public class ai.crossmeeting.app.MainActivity
-keep public class ai.crossmeeting.app.recording.RecordingService
-keep public class ai.crossmeeting.app.widget.MeetingWidgetReceiver
-keep public class ai.crossmeeting.app.widget.MeetingWidget
```

---

### SEC-05 — Alto | Ktor em versão Release Candidate em produção

**Descrição:**  
As dependências Ktor usam `3.0.0-rc-1`:

```kotlin
// app/build.gradle.kts:74-76
implementation("io.ktor:ktor-client-android:3.0.0-rc-1")
implementation("io.ktor:ktor-client-okhttp:3.0.0-rc-1")
implementation("io.ktor:ktor-client-websockets:3.0.0-rc-1")
```

Release Candidates podem conter bugs de segurança não divulgados, APIs instáveis e não recebem backports de CVEs de versões estáveis. O Ktor 3.0.0 estável foi lançado após o `rc-1`.

**Localização:** `app/build.gradle.kts:74-76`

**Impacto:**  
Exposição a vulnerabilidades não corrigidas em versão não-produção de uma lib que gerencia WebSocket com áudio de reuniões e tokens.

**Recomendação:**  
Atualizar para a versão estável:

```kotlin
val ktorVersion = "3.1.3" // ou a mais recente estável
implementation("io.ktor:ktor-client-android:$ktorVersion")
implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
implementation("io.ktor:ktor-client-websockets:$ktorVersion")
```

---

### SEC-06 — Médio | `Log.d` verboso em builds de release

**Descrição:**  
Múltiplos `Log.d` permanecem ativos em builds de release:

- `RecordingService.kt:122`: `Log.d(TAG, "hasPlayback=$hasPlayback")` — revela se captura de playback está ativa
- `RecordingService.kt:165`: `Log.d(TAG, "WebSocket conectado, hasPlayback=$hasPlayback")`
- `RecordingService.kt:228`: `Log.d(TAG, "AudioRecord mic+playback iniciados")`
- `RecordingService.kt:243`: `if (++frames % 20 == 0) Log.d(TAG, "mixed frames=$frames")` — 1 log a cada 20 frames, contínuo durante gravação
- `RecordingScreen.kt:122`: `Log.d("CMSave", "enhance ok para meetingId=$meetingId body=${bodyText.take(200)}")` — **os primeiros 200 caracteres da resposta de IA ficam nos logs**

O trecho de `RecordingService.kt:302` tem tratamento correto (`if (BuildConfig.DEBUG)` antes de logar o payload Deepgram bruto), mas os demais não têm.

**Localização:** Listados acima

**Impacto:**  
Em dispositivos rooteados ou com ADB conectado, `adb logcat` expõe informações de estado da gravação e parcialmente o conteúdo das notas geradas pela IA. O body do enhance pode conter resumo, ações e decisões da reunião.

**Recomendação:**  
Envolver todos os `Log.d` de dados de negócio com guarda de DEBUG:

```kotlin
if (BuildConfig.DEBUG) Log.d(TAG, "WebSocket conectado, hasPlayback=$hasPlayback")
if (BuildConfig.DEBUG) Log.d("CMSave", "enhance ok: body=${bodyText.take(200)}")
```

Para logs de frames (muito frequentes), remover completamente em release:
```kotlin
if (BuildConfig.DEBUG && ++frames % 20 == 0) Log.d(TAG, "mixed frames=$frames")
```

---

### SEC-07 — Médio | Falha silenciosa no `refreshCurrentSession()` sem tratamento

**Descrição:**  
No `MainActivity.kt:74-79`:

```kotlin
scope.launch {
    isRefreshingSession = true
    runCatching {
        SupabaseClientProvider.client.auth.refreshCurrentSession()
    }
    isRefreshingSession = false
}
```

A falha do `refreshCurrentSession()` é completamente silenciada pelo `runCatching` sem `.onFailure`. Se o refresh falhar (sem rede, token expirado, sessão revogada), `isRefreshingSession` vai para `false` e o app renderiza as telas normalmente. O Supabase SDK pode ou não atualizar o `sessionStatus` para `NotAuthenticated` dependendo do tipo de falha.

**Localização:** `MainActivity.kt:76-79`

**Impacto:**  
Em cenários de sessão expirada sem rede disponível, o usuário pode permanecer com acesso aparente à UI enquanto requests subsequentes ao Supabase falham silenciosamente. Não há mecanismo de logout forçado em caso de falha de refresh.

**Recomendação:**

```kotlin
runCatching {
    SupabaseClientProvider.client.auth.refreshCurrentSession()
}.onFailure { e ->
    // Se for erro de autenticação (401/403), força sign out
    if (e is io.github.jan.supabase.exceptions.RestException && e.statusCode in listOf(401, 403)) {
        SupabaseClientProvider.client.auth.signOut()
    }
    // Outros erros (rede) são silenciados — sessão pode estar apenas offline
    if (BuildConfig.DEBUG) Log.w("CMAuth", "refresh falhou: ${e.message}")
}
```

---

### SEC-08 — Médio | `MeetingWidgetReceiver` exported sem `android:permission`

**Descrição:**  
No `AndroidManifest.xml:43-51`:

```xml
<receiver
    android:name=".widget.MeetingWidgetReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
```

O receiver é necessariamente exported para AppWidgets, mas não tem `android:permission` attribute. Qualquer app no dispositivo pode enviar um broadcast com action `android.appwidget.action.APPWIDGET_UPDATE` para o receiver, disparando `refreshWidgetState()` que faz uma query ao Supabase a cada chamada.

**Localização:** `AndroidManifest.xml:43-44` / `MeetingWidget.kt:116-121`

**Impacto:**  
Um app malicioso pode triggerar chamadas repetidas ao Supabase via o receiver, causando consumo excessivo de bateria e potencialmente atingindo rate limits da API. O widget também faz query sem checar se há sessão válida (`SupabaseClientProvider.client` pode estar sem autenticação).

**Recomendação:**  

```xml
<receiver
    android:name=".widget.MeetingWidgetReceiver"
    android:exported="true"
    android:permission="android.permission.BIND_APPWIDGET">
```

E em `refreshWidgetState`, verificar sessão antes de consultar:

```kotlin
suspend fun refreshWidgetState(context: Context) {
    val session = SupabaseClientProvider.client.auth.currentSessionOrNull()
    if (session == null) return  // sem sessão, não consulta
    // ... resto do código
}
```

---

### SEC-09 — Médio | Notificação do Foreground Service visível na lock screen

**Descrição:**  
O canal de notificação em `RecordingService.kt:376` define:

```kotlin
lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
```

E a notificação em si usa:
```kotlin
.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
```

Isso faz a notificação completa aparecer na tela de bloqueio sem desbloqueio. O conteúdo atual é genérico ("🎙 Crossmeeting gravando" / "Transcrevendo sua reunião ao vivo"), mas qualquer mudança futura no texto da notificação que inclua título da reunião ou nome de participante ficaria visível para qualquer pessoa que olhe para o dispositivo bloqueado.

**Localização:** `RecordingService.kt:373-396`

**Impacto:**  
Exposição de metadados de reuniões a pessoas próximas do dispositivo bloqueado se o texto for enriquecido futuramente.

**Recomendação:**  
Mudar para `VISIBILITY_PRIVATE` no canal e usar `publicVersion` com texto genérico:

```kotlin
lockscreenVisibility = android.app.Notification.VISIBILITY_PRIVATE
```

```kotlin
.setPublicVersion(
    NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Crossmeeting")
        .setContentText("Gravação em andamento")
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .build()
)
.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
```

---

### SEC-10 — Baixo | Supabase, Ktor e OkHttp mantidos sem ofuscação pelo ProGuard

**Descrição:**  
As regras ProGuard:

```
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
```

Mantêm toda a implementação das bibliotecas sem ofuscação no APK final. Isso facilita análise de como o app se comunica com o backend e pode revelar mecanismos de autenticação para um atacante analisando o binário.

**Localização:** `proguard-rules.pro:14-22`

**Impacto:** Baixo — facilita engenharia reversa do protocolo de comunicação, mas não expõe credenciais por si só.

**Recomendação:**  
Usar regras mais cirúrgicas. O supabase-kt/ktor normalmente fornecem consumer rules próprias via AAR (`proguard.txt` embutido). Verificar se as consumer rules das libs já cobrem o necessário antes de adicionar rules manuais. Remover os `-keep` manuais das libs e testar o release build para confirmar que funciona com as consumer rules nativas.

---

### SEC-11 — Baixo | Compose BOM desatualizada

**Descrição:**  
`app/build.gradle.kts:54` usa `compose-bom:2024.09.00`, datando de setembro de 2024. A versão atual (julho 2026) é significativamente mais nova, com correções de bugs e possivelmente correções de segurança.

**Localização:** `app/build.gradle.kts:54`

**Recomendação:**

```kotlin
implementation(platform("androidx.compose:compose-bom:2025.05.00")) // ou a mais recente
```

Atualizar também as dependências relacionadas (`activity-compose`, `lifecycle-*`).

---

### SEC-12 — Informativo | `SUPABASE_ANON_KEY` embutida no APK via BuildConfig

**Descrição:**  
A chave anon do Supabase (`SUPABASE_ANON_KEY`) é injetada no APK via `BuildConfig` a partir do `local.properties`. Qualquer pessoa que faça engenharia reversa do APK consegue extrair essa chave com ferramentas como `apktool` ou `jadx`.

**Localização:** `SupabaseClient.kt:15`, `app/build.gradle.kts:28`

**Impacto:**  
A chave anon do Supabase é projetada para ser pública e é protegida por RLS (Row Level Security). A extração da chave permite chamadas diretas à API Supabase, mas sem um JWT de usuário válido as RLS policies bloqueiam acesso a dados. O risco real depende inteiramente da solidez das policies RLS no backend.

**Observação:** Este padrão é o design canônico do Supabase para clientes mobile. Não é um erro de implementação, mas deve ser documentado como pressuposto de segurança: **as RLS policies são a última linha de defesa**.

**Recomendação:**  
Auditar periodicamente as RLS policies no Supabase para garantir que nenhuma tabela esteja com `USING (true)` sem restrição de usuário.

---

### SEC-13 — Informativo | Conteúdo de transcrição exibido sem sanitização HTML

**Descrição:**  
`MeetingDetailScreen.kt:285` renderiza o texto da transcrição diretamente via `Text()` do Compose:

```kotlin
Text(
    seg.text,
    style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurface,
)
```

O Jetpack Compose `Text()` renderiza como texto puro — não interpreta HTML, Markdown ou JavaScript. Portanto, não há risco de XSS ou injeção de código na camada de UI.

**Observação:** O risco de prompt injection existe na Edge Function `chat-ask` (backend), mas está fora do escopo deste app Android. O comentário no `ChatScreen.kt:28` menciona "regras anti-prompt-injection do desktop", o que sugere consciência do problema no backend.

**Recomendação:**  
Manter o padrão atual (texto puro). Se no futuro for adicionado suporte a Markdown na transcrição (usando libs como `Markwon`), garantir que HTML embedding seja desabilitado.

---

## Pontos Positivos

A análise identificou diversas boas práticas que merecem reconhecimento:

1. **Chave anon sem service role key** — `SupabaseClient.kt` usa apenas a anon key. O comentário explícito no código documenta essa decisão (`nunca a service role key`).

2. **Token Deepgram efêmero** — A Edge Function `deepgram-token` é invocada a cada sessão de gravação, evitando expor a chave permanente do Deepgram no APK.

3. **Cleartext desabilitado globalmente** — `android:usesCleartextTraffic="false"` no manifest previne conexões HTTP acidentais no app como um todo.

4. **RecordingService não exportado** — `android:exported="false"` impede que apps externos iniciem gravações.

5. **Backup de SharedPreferences desabilitado** — `data_extraction_rules.xml` exclui SharedPreferences de backups na nuvem e transferências entre dispositivos, protegendo tokens de sessão Supabase.

6. **TLS 1.2+ forçado no WebSocket** — `ConnectionSpec.MODERN_TLS` com `TLS_1_3` e `TLS_1_2` no `tlsSpec`. (O problema SEC-03 é a adição redundante do CLEARTEXT junto.)

7. **Wake lock com timeout** — `acquire(3 * 60 * 60 * 1000L)` tem um limite máximo de 3h, evitando wake lock infinito.

8. **`allowBackup="false"`** — Desabilita backup do app no Android Backup Service.

9. **Refresh de sessão no `ON_RESUME`** — Proativo para lidar com Doze mode.

10. **Verificação de permissão em runtime** — `RECORD_AUDIO` é verificado com `ContextCompat.checkSelfPermission` antes de iniciar gravação.

11. **Widget sem dados sensíveis** — Exibe apenas título e data da última reunião, não transcript ou enhancement.

---

## Próximos Passos Priorizados

### Sprint imediato (antes de qualquer release público)

1. **SEC-01** — Adicionar `android:foregroundServiceType="microphone|mediaProjection"` e a permissão `FOREGROUND_SERVICE_MEDIA_PROJECTION` no manifest. Testar em dispositivo Android 14.

2. **SEC-02** — Avaliar migração para HTTPS App Links com Digital Asset Links. Se o calendário de lançamento não permitir, adicionar validação explícita do intent no `onNewIntent` como mitigação parcial.

3. **SEC-03** — Remover `ConnectionSpec.CLEARTEXT` do cliente WebSocket. Testar conexão WSS após a mudança.

4. **SEC-05** — Atualizar Ktor de `3.0.0-rc-1` para a versão estável mais recente.

### Sprint seguinte (antes de escala de usuários)

5. **SEC-04** — Refinar regras ProGuard para habilitar ofuscação do código da aplicação. Validar com build de release e testes de regressão.

6. **SEC-06** — Auditar todos os `Log.d`/`Log.e` e envolver chamadas com dados de negócio em `if (BuildConfig.DEBUG)`.

7. **SEC-07** — Adicionar tratamento de falha explícito no `refreshCurrentSession()`.

8. **SEC-08** — Adicionar `android:permission="android.permission.BIND_APPWIDGET"` ao receiver do widget e checar sessão válida antes de fazer query.

### Manutenção contínua

9. **SEC-11** — Atualizar Compose BOM e dependências AndroidX mensalmente.

10. **SEC-09** — Mudar visibilidade da notificação para `VISIBILITY_PRIVATE` como precaução preventiva.

11. **SEC-12** — Auditar RLS policies no Supabase a cada nova tabela/feature adicionada.

---

*Gerado por análise estática do código-fonte. Análise dinâmica (testes em dispositivo, análise de tráfego de rede, fuzzing) não foi realizada e pode revelar achados adicionais.*
