# ── Kotlin Serialization ──────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Supabase / Ktor ───────────────────────────────────────────────────────────
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn io.github.jan.supabase.**

# ── OkHttp ───────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ── Crossmeeting — apenas o que o Android instancia por nome ──────────────────
# Entrypoints do sistema (Activity, Service, Receiver) precisam de nome preservado.
# O resto do código da aplicação é ofuscado normalmente pelo R8.
-keep public class ai.crossmeeting.app.MainActivity
-keep public class ai.crossmeeting.app.recording.RecordingService
-keep public class ai.crossmeeting.app.widget.MeetingWidgetReceiver
-keep public class ai.crossmeeting.app.SupabaseClientProvider

# ── Coroutines ────────────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# ── Glance (widget) ───────────────────────────────────────────────────────────
-keep class androidx.glance.** { *; }
