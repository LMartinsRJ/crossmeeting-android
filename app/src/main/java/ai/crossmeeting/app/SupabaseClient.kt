package ai.crossmeeting.app

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest

/**
 * Cliente Supabase único do app — mesma URL/projeto usados pelo desktop (Electron) e pelo
 * crossmeeting-web. Usa a chave anon (pública, protegida por RLS), nunca a service role key.
 */
object SupabaseClientProvider {
    val client = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
    ) {
        install(Auth) {
            // Usado para construir o deep link de retorno do login (crossmeeting://login-callback)
            scheme = "crossmeeting"
            host = "login-callback"
        }
        install(Postgrest)
        install(Functions)
    }
}
