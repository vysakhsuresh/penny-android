package app.penny.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import app.penny.R

/**
 * Starts another app's screen without crashing when nothing on the phone can handle it (no UPI app, no browser,
 * notification-access settings missing on Android Go, …). Returns whether something opened.
 */
/** Drops the invisible bidi isolates money labels carry on screen; pasted into chat apps they can show as boxes. */
fun plainShareText(s: String): String = s.replace(Regex("[⁦⁧⁨⁩]"), "")

private fun cleanShare(intent: Intent): Intent {
    val inner = if (intent.action == Intent.ACTION_CHOOSER) intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) else intent
    if (inner?.action == Intent.ACTION_SEND) inner.getStringExtra(Intent.EXTRA_TEXT)?.let { inner.putExtra(Intent.EXTRA_TEXT, plainShareText(it)) }
    if (intent.action == Intent.ACTION_CHOOSER && inner != null) intent.putExtra(Intent.EXTRA_INTENT, inner)
    return intent
}

fun Context.safeStart(intent: Intent): Boolean = try {
    startActivity(cleanShare(intent))
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(this, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
    false
} catch (_: SecurityException) {
    Toast.makeText(this, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
    false
}
