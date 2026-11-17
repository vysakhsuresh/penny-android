package app.penny.core

/**
 * The single place for brand strings. A rename is one change here; the app module reads
 * [NAME] at build time (see app/build.gradle.kts) so the launcher label follows it.
 */
object Brand {
    const val NAME = "Penny"
    const val TAGLINE = "Know what is safe to spend."
    const val DEEP_LINK_SCHEME = "penny"
    const val EXPORT_APP_ID = "penny"

    /** Company contact, the same as every Layerbit app (taken from LayerLink's BrandLinks, see DECISIONS.md). */
    const val SUPPORT_EMAIL = "ceo@layerbit.co.in"
    /** Support on WhatsApp, international digits without "+" (e.g. "919800000000"). Empty hides the option. */
    const val SUPPORT_WHATSAPP = "916282595823"
    /** Layerbit's site-wide policy for now; switch to layerbit.co.in/penny-app-privacy.html once that page is live. */
    const val PRIVACY_URL = "https://layerbit.co.in/privacy.html"
    const val WEB_URL = "https://layerbit.co.in"
    /**
     * Relay base URL per residency region (SE-11); configurable at build time for self-hosting. Still a placeholder:
     * the relay service is not deployed yet.
     */
    const val RELAY_URL = "https://relay.penny.example"
}
