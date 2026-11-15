package app.penny

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import app.penny.domain.AppPrefs
import app.penny.ui.common.UndoController
import app.penny.ui.common.UndoHost
import app.penny.ui.lock.LockScreen
import app.penny.ui.theme.PennyTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single activity. AppCompat so BiometricPrompt works (SE-04). Locks when returning after the auto-lock delay, hides
 * content in the app switcher while locked, and blocks screenshots when the user asks (ST-08).
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var undo: UndoController
    @Inject lateinit var prefs: AppPrefs

    private var locked by mutableStateOf(false)
    /** Deep links that arrive while the app is open (widgets, tile, shortcuts, notifications). */
    private var pendingIntent by mutableStateOf<android.content.Intent?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingIntent = intent
    }
    private var backgroundAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        locked = savedInstanceState?.getBoolean("locked") ?: prefs.security.value.lockEnabled
        applySecureFlag()
        val start = if (prefs.currentProfileId.value == null) Routes.ONBOARDING else Routes.main()
        setContent {
            val appearance by prefs.appearance.collectAsStateWithLifecycle()
            val security by prefs.security.collectAsStateWithLifecycle()
            PennyTheme(appearance) {
                Surface {
                    if (locked && security.lockEnabled) {
                        LockScreen(onUnlocked = { locked = false })
                    } else {
                        val nav = rememberNavController()
                        UndoHost(undo) { PennyNavHost(nav, start, appearance.reduceMotion) }
                        androidx.compose.runtime.LaunchedEffect(pendingIntent) {
                            pendingIntent?.let { if (it.data != null) nav.handleDeepLink(it) }
                            pendingIntent = null
                        }
                    }
                }
            }
        }
    }

    private fun applySecureFlag() {
        val s = prefs.security.value
        // Amounts hidden in the app switcher while the lock is on (SE-04), or always when screenshots are blocked.
        if (s.blockScreenshots || s.lockEnabled) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStop() {
        super.onStop()
        backgroundAt = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        applySecureFlag()
        val s = prefs.security.value
        if (s.lockEnabled && backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt >= s.autoLockSeconds * 1000L) locked = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("locked", locked)
    }
}
