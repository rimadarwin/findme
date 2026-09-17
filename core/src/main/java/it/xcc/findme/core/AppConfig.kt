package it.xcc.findme.core

object AppConfig {
    val supabaseUrl: String get() = BuildConfig.SUPABASE_URL
    val supabasePublishableKey: String get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY
    val liveKitUrl: String get() = BuildConfig.LIVEKIT_URL

    val isConfigured: Boolean
        get() = supabaseUrl.startsWith("https://") &&
            supabasePublishableKey.isNotBlank() &&
            liveKitUrl.startsWith("wss://")
}
