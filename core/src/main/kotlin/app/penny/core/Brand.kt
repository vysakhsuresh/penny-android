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
}
