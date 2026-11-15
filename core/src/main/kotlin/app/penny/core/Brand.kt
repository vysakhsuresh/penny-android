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

    /** Placeholders on the reserved .example domain until the real ones exist (see DECISIONS.md, 2026-11-15). */
    const val SUPPORT_EMAIL = "support@penny.example"
    const val PRIVACY_URL = "https://penny.example/privacy"
    const val WEB_URL = "https://penny.example"
    /** Relay base URL per residency region (SE-11); configurable at build time for self-hosting. */
    const val RELAY_URL = "https://relay.penny.example"
}
