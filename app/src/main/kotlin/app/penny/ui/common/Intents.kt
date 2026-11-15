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
fun Context.safeStart(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(this, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
    false
} catch (_: SecurityException) {
    Toast.makeText(this, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
    false
}
